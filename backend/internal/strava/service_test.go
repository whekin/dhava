package strava

import (
	"context"
	"errors"
	"net/url"
	"testing"
	"time"

	"github.com/whekin/nakvali/backend/internal/store"
)

const testDeviceToken = "test-device-token-that-is-at-least-thirty-two-bytes"

type fakeRepository struct {
	connection     store.StravaConnection
	stateHash      []byte
	deviceHash     []byte
	stateExpires   time.Time
	export         store.StravaExport
	updatedAccess  string
	updatedRefresh string
	updatedExpires time.Time
	completed      bool
	revoked        bool
}

func (f *fakeRepository) BeginStravaConnection(
	_ context.Context,
	deviceHash []byte,
	stateHash []byte,
	expires time.Time,
) error {
	f.deviceHash = deviceHash
	f.stateHash = stateHash
	f.stateExpires = expires
	return nil
}

func (f *fakeRepository) StravaConnectionByOAuthState(
	_ context.Context,
	stateHash []byte,
	_ time.Time,
) (store.StravaConnection, error) {
	if string(stateHash) != string(f.stateHash) {
		return store.StravaConnection{}, store.ErrStravaConnectionNotFound
	}
	return f.connection, nil
}

func (f *fakeRepository) CompleteStravaConnection(
	_ context.Context,
	_ string,
	_ int64,
	_ string,
	_ string,
	_ string,
	_ string,
	_ time.Time,
) error {
	f.completed = true
	return nil
}

func (f *fakeRepository) StravaConnectionByDeviceToken(
	_ context.Context,
	deviceHash []byte,
) (store.StravaConnection, error) {
	if len(f.deviceHash) > 0 && string(deviceHash) != string(f.deviceHash) {
		return store.StravaConnection{}, store.ErrStravaConnectionNotFound
	}
	return f.connection, nil
}

func (f *fakeRepository) UpdateStravaTokens(
	_ context.Context,
	_ string,
	access string,
	refresh string,
	expires time.Time,
) error {
	f.updatedAccess = access
	f.updatedRefresh = refresh
	f.updatedExpires = expires
	return nil
}

func (f *fakeRepository) RevokeStravaConnection(context.Context, string) error {
	f.revoked = true
	return nil
}

func (f *fakeRepository) UpsertStravaExport(
	_ context.Context,
	connectionID string,
	externalID string,
	title string,
	description string,
	sportType string,
) (store.StravaExport, error) {
	if f.export.ConnectionID == "" {
		f.export = store.StravaExport{
			ConnectionID: connectionID,
			ExternalID:   externalID,
			Title:        title,
			Description:  description,
			SportType:    sportType,
			Status:       "queued",
		}
	}
	return f.export, nil
}

func (f *fakeRepository) MarkStravaExportProcessing(
	_ context.Context,
	_ string,
	_ string,
	uploadID int64,
) error {
	f.export.StravaUploadID = &uploadID
	f.export.Status = "processing"
	return nil
}

func (f *fakeRepository) MarkStravaExportUploaded(
	_ context.Context,
	_ string,
	_ string,
	activityID int64,
) error {
	f.export.StravaActivityID = &activityID
	f.export.Status = "uploaded"
	return nil
}

func (f *fakeRepository) MarkStravaExportFailed(
	_ context.Context,
	_ string,
	_ string,
	message string,
) error {
	f.export.Status = "failed"
	f.export.Error = &message
	return nil
}

type fakeAPI struct {
	exchange        TokenResponse
	refresh         TokenResponse
	created         UploadResponse
	polled          UploadResponse
	createCalls     int
	pollCalls       int
	updatedSport    string
	updatedActivity int64
	refreshErr      error
}

func (f *fakeAPI) ExchangeCode(context.Context, string) (TokenResponse, error) {
	return f.exchange, nil
}

func (f *fakeAPI) Refresh(context.Context, string) (TokenResponse, error) {
	return f.refresh, f.refreshErr
}

func (f *fakeAPI) CreateUpload(
	_ context.Context,
	_ string,
	_ CreateUploadRequest,
) (UploadResponse, error) {
	f.createCalls++
	return f.created, nil
}

func (f *fakeAPI) UploadStatus(
	_ context.Context,
	_ string,
	_ int64,
) (UploadResponse, error) {
	f.pollCalls++
	return f.polled, nil
}

func (f *fakeAPI) UpdateActivitySport(
	_ context.Context,
	_ string,
	activityID int64,
	sportType string,
) error {
	f.updatedActivity = activityID
	f.updatedSport = sportType
	return nil
}

func connectedConnection(now time.Time) store.StravaConnection {
	access := "access"
	refresh := "refresh"
	expires := now.Add(2 * time.Hour)
	return store.StravaConnection{
		ID:              "connection-id",
		AccessToken:     &access,
		RefreshToken:    &refresh,
		AccessExpiresAt: &expires,
		Status:          "connected",
	}
}

func testService(repo *fakeRepository, api *fakeAPI, now time.Time) *Service {
	service := NewService(repo, api, Config{
		ClientID:       "123",
		PublicBaseURL:  "https://api.example.test",
		AppRedirectURL: "nakvali://strava/connected",
	})
	service.now = func() time.Time { return now }
	service.random = func(bytes []byte) (int, error) {
		for index := range bytes {
			bytes[index] = byte(index)
		}
		return len(bytes), nil
	}
	return service
}

func TestBeginConnectBuildsMobileOAuthAndStoresHashedSecrets(t *testing.T) {
	now := time.Unix(1_780_000_000, 0)
	repo := &fakeRepository{}
	service := testService(repo, &fakeAPI{}, now)

	start, err := service.BeginConnect(context.Background(), testDeviceToken)
	if err != nil {
		t.Fatal(err)
	}
	authorizeURL, err := url.Parse(start.AuthorizeURL)
	if err != nil {
		t.Fatal(err)
	}
	if authorizeURL.Path != "/oauth/mobile/authorize" {
		t.Fatalf("path = %q", authorizeURL.Path)
	}
	query := authorizeURL.Query()
	if query.Get("scope") != "activity:write" {
		t.Errorf("scope = %q", query.Get("scope"))
	}
	if query.Get("redirect_uri") != "https://api.example.test/api/v1/strava/oauth/callback" {
		t.Errorf("redirect_uri = %q", query.Get("redirect_uri"))
	}
	if string(repo.deviceHash) == testDeviceToken {
		t.Error("device credential was stored without hashing")
	}
	if got := repo.stateExpires; !got.Equal(now.Add(10 * time.Minute)) {
		t.Errorf("state expiry = %v", got)
	}
}

func TestCompleteConnectRequiresWriteScope(t *testing.T) {
	now := time.Unix(1_780_000_000, 0)
	repo := &fakeRepository{
		connection: store.StravaConnection{ID: "connection-id"},
		stateHash:  hash("state"),
	}
	api := &fakeAPI{exchange: TokenResponse{
		AccessToken:  "access",
		RefreshToken: "refresh",
		ExpiresAt:    now.Add(6 * time.Hour).Unix(),
		Scope:        "read",
		Athlete:      Athlete{ID: 42},
	}}
	service := testService(repo, api, now)

	err := service.CompleteConnect(context.Background(), "state", "code", "")
	if !errors.Is(err, ErrWriteScopeMissing) {
		t.Fatalf("error = %v, want ErrWriteScopeMissing", err)
	}
	if repo.completed {
		t.Error("connection completed without activity:write")
	}
}

func TestExportIsIdempotentAndPollsExistingUpload(t *testing.T) {
	now := time.Unix(1_780_000_000, 0)
	repo := &fakeRepository{connection: connectedConnection(now)}
	api := &fakeAPI{
		created: UploadResponse{IDStr: "9001", Status: "Your activity is still being processed."},
		polled:  UploadResponse{IDStr: "9001", ActivityID: int64Pointer(7002)},
	}
	service := testService(repo, api, now)
	input := ExportRequest{
		ExternalID: "nakvali-recording-algorithm.gpx",
		Title:      "Forest ride",
		SportType:  "MountainBikeRide",
		Filename:   "ride.gpx",
		GPX:        []byte("<gpx/>"),
	}

	first, err := service.Export(context.Background(), testDeviceToken, input)
	if err != nil {
		t.Fatal(err)
	}
	if first.Status != "processing" || api.createCalls != 1 {
		t.Fatalf("first = %+v, create calls = %d", first, api.createCalls)
	}
	second, err := service.Export(context.Background(), testDeviceToken, input)
	if err != nil {
		t.Fatal(err)
	}
	if second.Status != "uploaded" || second.StravaActivityID == nil ||
		*second.StravaActivityID != 7002 {
		t.Fatalf("second = %+v", second)
	}
	if api.createCalls != 1 || api.pollCalls != 1 {
		t.Fatalf("create=%d poll=%d", api.createCalls, api.pollCalls)
	}
	if api.updatedSport != "MountainBikeRide" || api.updatedActivity != 7002 {
		t.Errorf("activity update = %d %q", api.updatedActivity, api.updatedSport)
	}
}

func TestExportPersistsRotatedRefreshToken(t *testing.T) {
	now := time.Unix(1_780_000_000, 0)
	connection := connectedConnection(now)
	soon := now.Add(30 * time.Minute)
	connection.AccessExpiresAt = &soon
	repo := &fakeRepository{connection: connection}
	api := &fakeAPI{
		refresh: TokenResponse{
			AccessToken:  "new-access",
			RefreshToken: "new-refresh",
			ExpiresAt:    now.Add(6 * time.Hour).Unix(),
		},
		created: UploadResponse{IDStr: "12"},
	}
	service := testService(repo, api, now)

	_, err := service.Export(context.Background(), testDeviceToken, ExportRequest{
		ExternalID: "nakvali-id-v1.gpx",
		Title:      "Ride",
		SportType:  "MountainBikeRide",
		Filename:   "ride.gpx",
		GPX:        []byte("<gpx/>"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if repo.updatedAccess != "new-access" || repo.updatedRefresh != "new-refresh" {
		t.Fatalf("rotated tokens not stored: %q %q", repo.updatedAccess, repo.updatedRefresh)
	}
}

func TestDuplicateAfterAmbiguousRetryBecomesUploaded(t *testing.T) {
	now := time.Unix(1_780_000_000, 0)
	uploadID := int64(9001)
	repo := &fakeRepository{
		connection: connectedConnection(now),
		export: store.StravaExport{
			ConnectionID:   "connection-id",
			ExternalID:     "nakvali-id-v1.gpx",
			Title:          "Ride",
			SportType:      "MountainBikeRide",
			StravaUploadID: &uploadID,
			Status:         "processing",
		},
	}
	api := &fakeAPI{polled: UploadResponse{
		IDStr: "9001",
		Error: "ride.gpx duplicate of activity 7002",
	}}
	service := testService(repo, api, now)

	status, err := service.Export(context.Background(), testDeviceToken, ExportRequest{
		ExternalID: "nakvali-id-v1.gpx",
		Title:      "Ride",
		SportType:  "MountainBikeRide",
		Filename:   "ride.gpx",
		GPX:        []byte("<gpx/>"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if status.Status != "uploaded" || status.StravaActivityID == nil ||
		*status.StravaActivityID != 7002 {
		t.Fatalf("status = %+v", status)
	}
}

func TestUnauthorizedRefreshRevokesConnection(t *testing.T) {
	now := time.Unix(1_780_000_000, 0)
	connection := connectedConnection(now)
	expired := now.Add(-time.Minute)
	connection.AccessExpiresAt = &expired
	repo := &fakeRepository{connection: connection}
	api := &fakeAPI{refreshErr: &HTTPError{StatusCode: 401, Message: "Unauthorized"}}
	service := testService(repo, api, now)

	_, err := service.Export(context.Background(), testDeviceToken, ExportRequest{
		ExternalID: "nakvali-id-v1.gpx",
		Title:      "Ride",
		SportType:  "MountainBikeRide",
		Filename:   "ride.gpx",
		GPX:        []byte("<gpx/>"),
	})
	if !errors.Is(err, ErrNotConnected) {
		t.Fatalf("error = %v, want ErrNotConnected", err)
	}
	if !repo.revoked {
		t.Error("connection was not revoked after Strava 401")
	}
}

func int64Pointer(value int64) *int64 {
	return &value
}
