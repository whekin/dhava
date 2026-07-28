package api

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"testing"

	dhavastrava "github.com/whekin/dhava/backend/internal/strava"
)

type fakeStravaBroker struct {
	deviceToken string
	export      dhavastrava.ExportRequest
}

func (f *fakeStravaBroker) BeginConnect(
	_ context.Context,
	deviceToken string,
) (dhavastrava.ConnectStart, error) {
	f.deviceToken = deviceToken
	return dhavastrava.ConnectStart{AuthorizeURL: "https://www.strava.com/oauth/mobile/authorize"}, nil
}

func (f *fakeStravaBroker) CompleteConnect(
	context.Context,
	string,
	string,
	string,
) error {
	return nil
}

func (f *fakeStravaBroker) AppRedirectURL(result string) string {
	return "dhava://strava/connected?result=" + result
}

func (f *fakeStravaBroker) Connection(
	_ context.Context,
	deviceToken string,
) (dhavastrava.ConnectionStatus, error) {
	f.deviceToken = deviceToken
	return dhavastrava.ConnectionStatus{Connected: true, AthleteName: "Alex Rider"}, nil
}

func (f *fakeStravaBroker) Export(
	_ context.Context,
	deviceToken string,
	input dhavastrava.ExportRequest,
) (dhavastrava.ExportStatus, error) {
	f.deviceToken = deviceToken
	f.export = input
	uploadID := int64(9001)
	return dhavastrava.ExportStatus{
		Status:         "processing",
		StravaUploadID: &uploadID,
	}, nil
}

func testStravaRouter(t *testing.T, broker StravaBroker) http.Handler {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return newRouterWithOptions(
		logger,
		nil,
		newFakeDatastore(),
		&fakeBlobStore{},
		WithStravaBroker(broker),
	)
}

func TestBeginStravaConnectRequiresDeviceCredential(t *testing.T) {
	h := testStravaRouter(t, &fakeStravaBroker{})
	request := httptest.NewRequest(http.MethodPost, "/api/v1/strava/connect", nil)
	response := httptest.NewRecorder()

	h.ServeHTTP(response, request)

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusUnauthorized)
	}
}

func TestBeginStravaConnectReturnsAuthorizeURL(t *testing.T) {
	broker := &fakeStravaBroker{}
	h := testStravaRouter(t, broker)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/strava/connect", nil)
	request.Header.Set("Authorization", "Bearer device-secret")
	response := httptest.NewRecorder()

	h.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body=%s", response.Code, response.Body)
	}
	if broker.deviceToken != "device-secret" {
		t.Errorf("device token = %q", broker.deviceToken)
	}
}

func TestStravaExportAcceptsOnlyProcessedGPXFields(t *testing.T) {
	broker := &fakeStravaBroker{}
	h := testStravaRouter(t, broker)

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	_ = writer.WriteField("external_id", "dhava-ride-v1.gpx")
	_ = writer.WriteField("title", "Forest ride")
	_ = writer.WriteField("description", "Dry trails")
	_ = writer.WriteField("sport_type", "MountainBikeRide")
	part, err := writer.CreateFormFile("file", "ride.gpx")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = part.Write([]byte("<gpx/>"))
	_ = writer.Close()

	request := httptest.NewRequest(http.MethodPost, "/api/v1/strava/exports", &body)
	request.Header.Set("Authorization", "Bearer device-secret")
	request.Header.Set("Content-Type", writer.FormDataContentType())
	response := httptest.NewRecorder()

	h.ServeHTTP(response, request)

	if response.Code != http.StatusAccepted {
		t.Fatalf("status = %d, body=%s", response.Code, response.Body)
	}
	if broker.export.ExternalID != "dhava-ride-v1.gpx" {
		t.Errorf("external id = %q", broker.export.ExternalID)
	}
	if string(broker.export.GPX) != "<gpx/>" {
		t.Errorf("GPX = %q", broker.export.GPX)
	}
	if broker.export.SportType != "MountainBikeRide" {
		t.Errorf("sport type = %q", broker.export.SportType)
	}
}

func TestStravaRoutesReportUnconfiguredBroker(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	h := newRouter(logger, nil, newFakeDatastore(), &fakeBlobStore{})
	request := httptest.NewRequest(http.MethodGet, "/api/v1/strava/connection", nil)
	response := httptest.NewRecorder()

	h.ServeHTTP(response, request)

	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusServiceUnavailable)
	}
}
