// Package strava implements the small OAuth and upload surface Dhava needs.
package strava

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	defaultOAuthURL = "https://www.strava.com/oauth/token"
	defaultAPIURL   = "https://www.strava.com/api/v3"
)

// Client is the narrow Strava V3 HTTP client used by Service.
type Client struct {
	httpClient *http.Client
	oauthURL   string
	apiURL     string
	clientID   string
	secret     string
}

func NewClient(httpClient *http.Client, clientID, secret string) *Client {
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 30 * time.Second}
	}
	return &Client{
		httpClient: httpClient,
		oauthURL:   defaultOAuthURL,
		apiURL:     defaultAPIURL,
		clientID:   clientID,
		secret:     secret,
	}
}

type Athlete struct {
	ID        int64  `json:"id"`
	FirstName string `json:"firstname"`
	LastName  string `json:"lastname"`
}

type TokenResponse struct {
	AccessToken  string  `json:"access_token"`
	RefreshToken string  `json:"refresh_token"`
	ExpiresAt    int64   `json:"expires_at"`
	Scope        string  `json:"scope"`
	Athlete      Athlete `json:"athlete"`
}

func (c *Client) ExchangeCode(ctx context.Context, code string) (TokenResponse, error) {
	values := url.Values{
		"client_id":     {c.clientID},
		"client_secret": {c.secret},
		"code":          {code},
		"grant_type":    {"authorization_code"},
	}
	var response TokenResponse
	err := c.doForm(ctx, c.oauthURL, values, &response)
	return response, err
}

func (c *Client) Refresh(ctx context.Context, refreshToken string) (TokenResponse, error) {
	values := url.Values{
		"client_id":     {c.clientID},
		"client_secret": {c.secret},
		"refresh_token": {refreshToken},
		"grant_type":    {"refresh_token"},
	}
	var response TokenResponse
	err := c.doForm(ctx, c.oauthURL, values, &response)
	return response, err
}

type UploadResponse struct {
	IDStr      string `json:"id_str"`
	ExternalID string `json:"external_id"`
	Error      string `json:"error"`
	Status     string `json:"status"`
	ActivityID *int64 `json:"activity_id"`
}

func (u UploadResponse) UploadID() (int64, error) {
	if u.IDStr == "" {
		return 0, errors.New("strava response omitted upload id")
	}
	id, err := strconv.ParseInt(u.IDStr, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("parse strava upload id: %w", err)
	}
	return id, nil
}

type CreateUploadRequest struct {
	File        []byte
	Filename    string
	Title       string
	Description string
	ExternalID  string
}

func (c *Client) CreateUpload(
	ctx context.Context,
	accessToken string,
	input CreateUploadRequest,
) (UploadResponse, error) {
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	fields := map[string]string{
		"name":        input.Title,
		"description": input.Description,
		"data_type":   "gpx",
		"external_id": input.ExternalID,
	}
	for name, value := range fields {
		if value != "" {
			if err := writer.WriteField(name, value); err != nil {
				return UploadResponse{}, fmt.Errorf("write strava upload field: %w", err)
			}
		}
	}
	filePart, err := writer.CreateFormFile("file", input.Filename)
	if err != nil {
		return UploadResponse{}, fmt.Errorf("create strava upload part: %w", err)
	}
	if _, err := filePart.Write(input.File); err != nil {
		return UploadResponse{}, fmt.Errorf("write strava upload file: %w", err)
	}
	if err := writer.Close(); err != nil {
		return UploadResponse{}, fmt.Errorf("close strava upload body: %w", err)
	}

	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		c.apiURL+"/uploads",
		&body,
	)
	if err != nil {
		return UploadResponse{}, fmt.Errorf("build strava upload request: %w", err)
	}
	request.Header.Set("Authorization", "Bearer "+accessToken)
	request.Header.Set("Content-Type", writer.FormDataContentType())

	var response UploadResponse
	if err := c.doJSON(request, http.StatusCreated, &response); err != nil {
		return UploadResponse{}, err
	}
	return response, nil
}

func (c *Client) UploadStatus(
	ctx context.Context,
	accessToken string,
	uploadID int64,
) (UploadResponse, error) {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		fmt.Sprintf("%s/uploads/%d", c.apiURL, uploadID),
		nil,
	)
	if err != nil {
		return UploadResponse{}, fmt.Errorf("build strava upload status request: %w", err)
	}
	request.Header.Set("Authorization", "Bearer "+accessToken)
	var response UploadResponse
	if err := c.doJSON(request, http.StatusOK, &response); err != nil {
		return UploadResponse{}, err
	}
	return response, nil
}

func (c *Client) UpdateActivitySport(
	ctx context.Context,
	accessToken string,
	activityID int64,
	sportType string,
) error {
	payload, err := json.Marshal(map[string]string{"sport_type": sportType})
	if err != nil {
		return fmt.Errorf("encode strava activity update: %w", err)
	}
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPut,
		fmt.Sprintf("%s/activities/%d", c.apiURL, activityID),
		bytes.NewReader(payload),
	)
	if err != nil {
		return fmt.Errorf("build strava activity update request: %w", err)
	}
	request.Header.Set("Authorization", "Bearer "+accessToken)
	request.Header.Set("Content-Type", "application/json")
	return c.doJSON(request, http.StatusOK, nil)
}

func (c *Client) doForm(
	ctx context.Context,
	endpoint string,
	values url.Values,
	output any,
) error {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		endpoint,
		strings.NewReader(values.Encode()),
	)
	if err != nil {
		return fmt.Errorf("build strava oauth request: %w", err)
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	return c.doJSON(request, http.StatusOK, output)
}

func (c *Client) doJSON(request *http.Request, wantStatus int, output any) error {
	response, err := c.httpClient.Do(request)
	if err != nil {
		return fmt.Errorf("strava request: %w", err)
	}
	defer response.Body.Close()

	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return fmt.Errorf("read strava response: %w", err)
	}
	if response.StatusCode != wantStatus {
		return &HTTPError{
			StatusCode: response.StatusCode,
			Message:    compactError(body),
		}
	}
	if output == nil || len(body) == 0 {
		return nil
	}
	if err := json.Unmarshal(body, output); err != nil {
		return fmt.Errorf("decode strava response: %w", err)
	}
	return nil
}

type HTTPError struct {
	StatusCode int
	Message    string
}

func (e *HTTPError) Error() string {
	if e.Message == "" {
		return fmt.Sprintf("strava HTTP %d", e.StatusCode)
	}
	return fmt.Sprintf("strava HTTP %d: %s", e.StatusCode, e.Message)
}

func compactError(body []byte) string {
	text := strings.Join(strings.Fields(string(body)), " ")
	if len(text) > 500 {
		text = text[:500]
	}
	return text
}
