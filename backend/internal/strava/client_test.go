package strava

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestClientExchangesCodeAndUploadsGPX(t *testing.T) {
	var sawUpload bool
	httpClient := &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		switch {
		case r.URL.Path == "/oauth/token":
			if err := r.ParseForm(); err != nil {
				t.Fatal(err)
			}
			if r.Form.Get("client_secret") != "secret" ||
				r.Form.Get("code") != "one-time-code" {
				t.Errorf("oauth form = %v", r.Form)
			}
			return jsonResponse(http.StatusOK, map[string]any{
				"access_token":  "access",
				"refresh_token": "refresh",
				"expires_at":    1_800_000_000,
				"scope":         "activity:write",
				"athlete":       map[string]any{"id": 42},
			}), nil
		case r.URL.Path == "/api/uploads":
			if r.Header.Get("Authorization") != "Bearer access" {
				t.Errorf("authorization = %q", r.Header.Get("Authorization"))
			}
			if err := r.ParseMultipartForm(1 << 20); err != nil {
				t.Fatal(err)
			}
			if r.FormValue("data_type") != "gpx" ||
				r.FormValue("external_id") != "dhava-id.gpx" {
				t.Errorf("upload form = %v", r.MultipartForm.Value)
			}
			file, _, err := r.FormFile("file")
			if err != nil {
				t.Fatal(err)
			}
			defer file.Close()
			data, _ := io.ReadAll(file)
			if string(data) != "<gpx/>" {
				t.Errorf("file = %q", data)
			}
			sawUpload = true
			return jsonResponse(
				http.StatusCreated,
				map[string]any{"id_str": "9001", "status": "processing"},
			), nil
		default:
			return jsonResponse(http.StatusNotFound, map[string]string{"error": "not found"}), nil
		}
	})}

	client := NewClient(httpClient, "123", "secret")
	client.oauthURL = "https://test.invalid/oauth/token"
	client.apiURL = "https://test.invalid/api"

	tokens, err := client.ExchangeCode(context.Background(), "one-time-code")
	if err != nil {
		t.Fatal(err)
	}
	if tokens.AccessToken != "access" || tokens.Athlete.ID != 42 {
		t.Fatalf("tokens = %+v", tokens)
	}
	upload, err := client.CreateUpload(context.Background(), tokens.AccessToken, CreateUploadRequest{
		File:       []byte("<gpx/>"),
		Filename:   "ride.gpx",
		Title:      "Ride",
		ExternalID: "dhava-id.gpx",
	})
	if err != nil {
		t.Fatal(err)
	}
	if id, err := upload.UploadID(); err != nil || id != 9001 {
		t.Fatalf("upload id = %d, error=%v", id, err)
	}
	if !sawUpload {
		t.Error("upload endpoint was not called")
	}
}

func TestClientUpdatesMountainBikeSportType(t *testing.T) {
	httpClient := &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.Method != http.MethodPut || r.URL.Path != "/activities/7002" {
			t.Errorf("request = %s %s", r.Method, r.URL.Path)
		}
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), `"sport_type":"MountainBikeRide"`) {
			t.Errorf("body = %s", body)
		}
		return jsonResponse(http.StatusOK, map[string]any{"id": 7002}), nil
	})}

	client := NewClient(httpClient, "123", "secret")
	client.apiURL = "https://test.invalid"
	if err := client.UpdateActivitySport(
		context.Background(),
		"access",
		7002,
		"MountainBikeRide",
	); err != nil {
		t.Fatal(err)
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (fn roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return fn(request)
}

func jsonResponse(status int, value any) *http.Response {
	var body strings.Builder
	_ = json.NewEncoder(&body).Encode(value)
	return &http.Response{
		StatusCode: status,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(strings.NewReader(body.String())),
	}
}
