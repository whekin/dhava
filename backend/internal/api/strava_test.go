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

	nakvalistrava "github.com/whekin/nakvali/backend/internal/strava"
)

type fakeStravaBroker struct {
	deviceToken string
	export      nakvalistrava.ExportRequest
}

func (f *fakeStravaBroker) BeginConnect(
	_ context.Context,
	deviceToken string,
) (nakvalistrava.ConnectStart, error) {
	f.deviceToken = deviceToken
	return nakvalistrava.ConnectStart{AuthorizeURL: "https://www.strava.com/oauth/mobile/authorize"}, nil
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
	return "nakvali://strava/connected?result=" + result
}

func (f *fakeStravaBroker) Connection(
	_ context.Context,
	deviceToken string,
) (nakvalistrava.ConnectionStatus, error) {
	f.deviceToken = deviceToken
	return nakvalistrava.ConnectionStatus{Connected: true, AthleteName: "Alex Rider"}, nil
}

func (f *fakeStravaBroker) Export(
	_ context.Context,
	deviceToken string,
	input nakvalistrava.ExportRequest,
) (nakvalistrava.ExportStatus, error) {
	f.deviceToken = deviceToken
	f.export = input
	uploadID := int64(9001)
	return nakvalistrava.ExportStatus{
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
	_ = writer.WriteField("external_id", "nakvali-ride-v1.gpx")
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
	if broker.export.ExternalID != "nakvali-ride-v1.gpx" {
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
