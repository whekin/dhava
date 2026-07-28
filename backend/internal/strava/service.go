package strava

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/whekin/dhava/backend/internal/store"
)

const (
	oauthStateLifetime = 10 * time.Minute
	refreshBefore      = time.Hour
)

var duplicateActivityPattern = regexp.MustCompile(`(?i)duplicate of activity ([0-9]+)`)

type Repository interface {
	BeginStravaConnection(context.Context, []byte, []byte, time.Time) error
	StravaConnectionByOAuthState(context.Context, []byte, time.Time) (store.StravaConnection, error)
	CompleteStravaConnection(context.Context, string, int64, string, string, string, string, time.Time) error
	StravaConnectionByDeviceToken(context.Context, []byte) (store.StravaConnection, error)
	UpdateStravaTokens(context.Context, string, string, string, time.Time) error
	RevokeStravaConnection(context.Context, string) error
	UpsertStravaExport(context.Context, string, string, string, string, string) (store.StravaExport, error)
	MarkStravaExportProcessing(context.Context, string, string, int64) error
	MarkStravaExportUploaded(context.Context, string, string, int64) error
	MarkStravaExportFailed(context.Context, string, string, string) error
}

type API interface {
	ExchangeCode(context.Context, string) (TokenResponse, error)
	Refresh(context.Context, string) (TokenResponse, error)
	CreateUpload(context.Context, string, CreateUploadRequest) (UploadResponse, error)
	UploadStatus(context.Context, string, int64) (UploadResponse, error)
	UpdateActivitySport(context.Context, string, int64, string) error
}

type Config struct {
	ClientID       string
	PublicBaseURL  string
	AppRedirectURL string
}

type Service struct {
	repository Repository
	api        API
	config     Config
	now        func() time.Time
	random     func([]byte) (int, error)
}

func NewService(repository Repository, api API, config Config) *Service {
	return &Service{
		repository: repository,
		api:        api,
		config:     config,
		now:        time.Now,
		random:     rand.Read,
	}
}

type ConnectStart struct {
	AuthorizeURL string `json:"authorize_url"`
}

func (s *Service) BeginConnect(ctx context.Context, deviceToken string) (ConnectStart, error) {
	if err := validateDeviceToken(deviceToken); err != nil {
		return ConnectStart{}, err
	}
	state, err := s.randomToken()
	if err != nil {
		return ConnectStart{}, fmt.Errorf("generate oauth state: %w", err)
	}
	if err := s.repository.BeginStravaConnection(
		ctx,
		hash(deviceToken),
		hash(state),
		s.now().Add(oauthStateLifetime),
	); err != nil {
		return ConnectStart{}, err
	}

	redirectURI := strings.TrimRight(s.config.PublicBaseURL, "/") +
		"/api/v1/strava/oauth/callback"
	query := url.Values{
		"client_id":       {s.config.ClientID},
		"redirect_uri":    {redirectURI},
		"response_type":   {"code"},
		"approval_prompt": {"auto"},
		"scope":           {"activity:write"},
		"state":           {state},
	}
	return ConnectStart{
		AuthorizeURL: "https://www.strava.com/oauth/mobile/authorize?" + query.Encode(),
	}, nil
}

func (s *Service) CompleteConnect(
	ctx context.Context,
	state string,
	code string,
	grantedScope string,
) error {
	if state == "" || code == "" {
		return ErrInvalidOAuthCallback
	}
	connection, err := s.repository.StravaConnectionByOAuthState(ctx, hash(state), s.now())
	if err != nil {
		return err
	}
	tokens, err := s.api.ExchangeCode(ctx, code)
	if err != nil {
		return err
	}
	scope := tokens.Scope
	if scope == "" {
		scope = grantedScope
	}
	if !hasScope(scope, "activity:write") {
		return ErrWriteScopeMissing
	}
	if tokens.AccessToken == "" || tokens.RefreshToken == "" || tokens.Athlete.ID == 0 {
		return errors.New("strava oauth response is incomplete")
	}
	name := strings.TrimSpace(tokens.Athlete.FirstName + " " + tokens.Athlete.LastName)
	return s.repository.CompleteStravaConnection(
		ctx,
		connection.ID,
		tokens.Athlete.ID,
		name,
		scope,
		tokens.AccessToken,
		tokens.RefreshToken,
		time.Unix(tokens.ExpiresAt, 0).UTC(),
	)
}

func (s *Service) AppRedirectURL(result string) string {
	redirect, err := url.Parse(s.config.AppRedirectURL)
	if err != nil {
		return s.config.AppRedirectURL
	}
	query := redirect.Query()
	query.Set("result", result)
	redirect.RawQuery = query.Encode()
	return redirect.String()
}

type ConnectionStatus struct {
	Connected   bool   `json:"connected"`
	AthleteID   *int64 `json:"athlete_id,omitempty"`
	AthleteName string `json:"athlete_name,omitempty"`
}

func (s *Service) Connection(
	ctx context.Context,
	deviceToken string,
) (ConnectionStatus, error) {
	if err := validateDeviceToken(deviceToken); err != nil {
		return ConnectionStatus{}, err
	}
	connection, err := s.repository.StravaConnectionByDeviceToken(ctx, hash(deviceToken))
	if errors.Is(err, store.ErrStravaConnectionNotFound) {
		return ConnectionStatus{Connected: false}, nil
	}
	if err != nil {
		return ConnectionStatus{}, err
	}
	status := ConnectionStatus{
		Connected: connection.Status == "connected",
		AthleteID: connection.AthleteID,
	}
	if connection.AthleteName != nil {
		status.AthleteName = *connection.AthleteName
	}
	return status, nil
}

type ExportRequest struct {
	ExternalID  string
	Title       string
	Description string
	SportType   string
	Filename    string
	GPX         []byte
}

type ExportStatus struct {
	Status           string `json:"status"`
	StravaUploadID   *int64 `json:"strava_upload_id,omitempty"`
	StravaActivityID *int64 `json:"strava_activity_id,omitempty"`
	Error            string `json:"error,omitempty"`
}

func (s *Service) Export(
	ctx context.Context,
	deviceToken string,
	input ExportRequest,
) (ExportStatus, error) {
	if err := validateDeviceToken(deviceToken); err != nil {
		return ExportStatus{}, err
	}
	connection, err := s.repository.StravaConnectionByDeviceToken(ctx, hash(deviceToken))
	if err != nil {
		return ExportStatus{}, err
	}
	if connection.Status != "connected" {
		return ExportStatus{}, ErrNotConnected
	}
	accessToken, err := s.accessToken(ctx, &connection)
	if err != nil {
		return ExportStatus{}, err
	}

	export, err := s.repository.UpsertStravaExport(
		ctx,
		connection.ID,
		input.ExternalID,
		input.Title,
		input.Description,
		input.SportType,
	)
	if err != nil {
		return ExportStatus{}, err
	}
	if export.StravaActivityID != nil {
		return exportStatus(export), nil
	}

	if export.StravaUploadID == nil {
		response, err := s.api.CreateUpload(ctx, accessToken, CreateUploadRequest{
			File:        input.GPX,
			Filename:    input.Filename,
			Title:       input.Title,
			Description: input.Description,
			ExternalID:  input.ExternalID,
		})
		if err != nil {
			return ExportStatus{}, s.connectionError(ctx, connection.ID, err)
		}
		uploadID, err := response.UploadID()
		if err != nil {
			return ExportStatus{}, err
		}
		if err := s.repository.MarkStravaExportProcessing(
			ctx,
			connection.ID,
			input.ExternalID,
			uploadID,
		); err != nil {
			return ExportStatus{}, err
		}
		export.StravaUploadID = &uploadID
		export.Status = "processing"
		if response.Error != "" {
			if activityID, ok := duplicateActivityID(response.Error); ok {
				return s.finishExport(ctx, accessToken, export, activityID)
			}
			return s.failExport(ctx, export, response.Error)
		}
		if response.ActivityID != nil {
			return s.finishExport(ctx, accessToken, export, *response.ActivityID)
		}
		return exportStatus(export), nil
	}

	response, err := s.api.UploadStatus(ctx, accessToken, *export.StravaUploadID)
	if err != nil {
		return ExportStatus{}, s.connectionError(ctx, connection.ID, err)
	}
	if response.Error != "" {
		if activityID, ok := duplicateActivityID(response.Error); ok {
			return s.finishExport(ctx, accessToken, export, activityID)
		}
		return s.failExport(ctx, export, response.Error)
	}
	if response.ActivityID == nil {
		export.Status = "processing"
		return exportStatus(export), nil
	}
	return s.finishExport(ctx, accessToken, export, *response.ActivityID)
}

func (s *Service) finishExport(
	ctx context.Context,
	accessToken string,
	export store.StravaExport,
	activityID int64,
) (ExportStatus, error) {
	if err := s.api.UpdateActivitySport(ctx, accessToken, activityID, export.SportType); err != nil {
		return ExportStatus{}, s.connectionError(ctx, export.ConnectionID, err)
	}
	if err := s.repository.MarkStravaExportUploaded(
		ctx,
		export.ConnectionID,
		export.ExternalID,
		activityID,
	); err != nil {
		return ExportStatus{}, err
	}
	export.StravaActivityID = &activityID
	export.Status = "uploaded"
	export.Error = nil
	return exportStatus(export), nil
}

func (s *Service) failExport(
	ctx context.Context,
	export store.StravaExport,
	message string,
) (ExportStatus, error) {
	message = sanitizeMessage(message)
	if err := s.repository.MarkStravaExportFailed(
		ctx,
		export.ConnectionID,
		export.ExternalID,
		message,
	); err != nil {
		return ExportStatus{}, err
	}
	export.Status = "failed"
	export.Error = &message
	return exportStatus(export), nil
}

func (s *Service) accessToken(
	ctx context.Context,
	connection *store.StravaConnection,
) (string, error) {
	if connection.AccessToken == nil ||
		connection.RefreshToken == nil ||
		connection.AccessExpiresAt == nil {
		return "", ErrNotConnected
	}
	if connection.AccessExpiresAt.After(s.now().Add(refreshBefore)) {
		return *connection.AccessToken, nil
	}
	tokens, err := s.api.Refresh(ctx, *connection.RefreshToken)
	if err != nil {
		return "", s.connectionError(ctx, connection.ID, err)
	}
	if tokens.AccessToken == "" || tokens.RefreshToken == "" {
		return "", errors.New("strava refresh response is incomplete")
	}
	expiresAt := time.Unix(tokens.ExpiresAt, 0).UTC()
	if err := s.repository.UpdateStravaTokens(
		ctx,
		connection.ID,
		tokens.AccessToken,
		tokens.RefreshToken,
		expiresAt,
	); err != nil {
		return "", err
	}
	connection.AccessToken = &tokens.AccessToken
	connection.RefreshToken = &tokens.RefreshToken
	connection.AccessExpiresAt = &expiresAt
	return tokens.AccessToken, nil
}

func (s *Service) connectionError(ctx context.Context, connectionID string, err error) error {
	var httpError *HTTPError
	if !errors.As(err, &httpError) || httpError.StatusCode != http.StatusUnauthorized {
		return err
	}
	if revokeErr := s.repository.RevokeStravaConnection(ctx, connectionID); revokeErr != nil {
		return errors.Join(ErrNotConnected, revokeErr)
	}
	return ErrNotConnected
}

func exportStatus(export store.StravaExport) ExportStatus {
	status := ExportStatus{
		Status:           export.Status,
		StravaUploadID:   export.StravaUploadID,
		StravaActivityID: export.StravaActivityID,
	}
	if export.Error != nil {
		status.Error = *export.Error
	}
	return status
}

func (s *Service) randomToken() (string, error) {
	value := make([]byte, 32)
	if _, err := s.random(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}

func hash(value string) []byte {
	sum := sha256.Sum256([]byte(value))
	return sum[:]
}

func validateDeviceToken(value string) error {
	if len(value) < 32 || len(value) > 256 {
		return ErrInvalidDeviceToken
	}
	return nil
}

func hasScope(scopes, wanted string) bool {
	for _, scope := range strings.FieldsFunc(scopes, func(r rune) bool {
		return r == ' ' || r == ','
	}) {
		if scope == wanted {
			return true
		}
	}
	return false
}

func sanitizeMessage(value string) string {
	value = strings.Join(strings.Fields(value), " ")
	if len(value) > 500 {
		value = value[:500]
	}
	return value
}

func duplicateActivityID(message string) (int64, bool) {
	match := duplicateActivityPattern.FindStringSubmatch(message)
	if len(match) != 2 {
		return 0, false
	}
	id, err := strconv.ParseInt(match[1], 10, 64)
	return id, err == nil && id > 0
}

var (
	ErrInvalidDeviceToken   = errors.New("invalid device token")
	ErrInvalidOAuthCallback = errors.New("invalid oauth callback")
	ErrWriteScopeMissing    = errors.New("strava activity:write permission was not granted")
	ErrNotConnected         = errors.New("strava is not connected")
)
