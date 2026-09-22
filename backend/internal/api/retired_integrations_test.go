package api

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRetiredStravaEndpointsAreNotRouted(t *testing.T) {
	handler := NewRouter(slog.Default(), nil, nil)
	for _, path := range []string{"/api/v1/strava/connect", "/api/v1/strava/connection", "/api/v1/strava/oauth/callback", "/api/v1/strava/exports"} {
		for _, method := range []string{http.MethodGet, http.MethodPost} {
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, httptest.NewRequest(method, path, nil))
			if response.Code != http.StatusNotFound {
				t.Errorf("%s %s: got %d", method, path, response.Code)
			}
		}
	}
}
