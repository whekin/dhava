package api

import (
	"errors"
	"io"
	"mime"
	"net/http"
	"regexp"
	"strings"

	"github.com/whekin/nakvali/backend/internal/store"
	nakvalistrava "github.com/whekin/nakvali/backend/internal/strava"
)

const maxStravaGPXBytes = 32 << 20

var externalIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,200}$`)

func (s *Server) handleBeginStravaConnect(w http.ResponseWriter, r *http.Request) {
	if s.strava == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "strava_not_configured"})
		return
	}
	token, ok := deviceBearerToken(w, r)
	if !ok {
		return
	}
	start, err := s.strava.BeginConnect(r.Context(), token)
	if err != nil {
		s.writeStravaError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, start)
}

func (s *Server) handleStravaConnection(w http.ResponseWriter, r *http.Request) {
	if s.strava == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "strava_not_configured"})
		return
	}
	token, ok := deviceBearerToken(w, r)
	if !ok {
		return
	}
	connection, err := s.strava.Connection(r.Context(), token)
	if err != nil {
		s.writeStravaError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, connection)
}

func (s *Server) handleStravaOAuthCallback(w http.ResponseWriter, r *http.Request) {
	if s.strava == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "strava_not_configured"})
		return
	}

	result := "failed"
	if r.URL.Query().Get("error") == "access_denied" {
		result = "denied"
	} else {
		err := s.strava.CompleteConnect(
			r.Context(),
			r.URL.Query().Get("state"),
			r.URL.Query().Get("code"),
			r.URL.Query().Get("scope"),
		)
		if err == nil {
			result = "connected"
		} else {
			s.logger.Warn("strava oauth callback failed", "error", err)
		}
	}
	http.Redirect(w, r, s.strava.AppRedirectURL(result), http.StatusFound)
}

func (s *Server) handleStravaExport(w http.ResponseWriter, r *http.Request) {
	if s.strava == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "strava_not_configured"})
		return
	}
	token, ok := deviceBearerToken(w, r)
	if !ok {
		return
	}
	contentType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || contentType != "multipart/form-data" {
		writeJSON(w, http.StatusUnsupportedMediaType, map[string]string{"error": "multipart_required"})
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxStravaGPXBytes+(1<<20))
	if err := r.ParseMultipartForm(1 << 20); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "gpx_too_large"})
		} else {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_multipart"})
		}
		return
	}
	defer r.MultipartForm.RemoveAll()

	externalID := r.FormValue("external_id")
	title := strings.TrimSpace(r.FormValue("title"))
	description := strings.TrimSpace(r.FormValue("description"))
	sportType := r.FormValue("sport_type")
	if sportType == "" {
		sportType = "MountainBikeRide"
	}
	if !externalIDPattern.MatchString(externalID) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_external_id"})
		return
	}
	if title == "" || len([]rune(title)) > maxTitleLen {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_title"})
		return
	}
	if len([]rune(description)) > maxDescriptionLen {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "description_too_long"})
		return
	}
	if sportType != "MountainBikeRide" && sportType != "EMountainBikeRide" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_sport_type"})
		return
	}

	file, header, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "gpx_required"})
		return
	}
	defer file.Close()
	gpx, err := io.ReadAll(io.LimitReader(file, maxStravaGPXBytes+1))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "gpx_unreadable"})
		return
	}
	if len(gpx) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "gpx_empty"})
		return
	}
	if len(gpx) > maxStravaGPXBytes {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "gpx_too_large"})
		return
	}

	status, err := s.strava.Export(r.Context(), token, nakvalistrava.ExportRequest{
		ExternalID:  externalID,
		Title:       title,
		Description: description,
		SportType:   sportType,
		Filename:    header.Filename,
		GPX:         gpx,
	})
	if err != nil {
		s.writeStravaError(w, err)
		return
	}
	switch status.Status {
	case "uploaded":
		writeJSON(w, http.StatusOK, status)
	case "failed":
		writeJSON(w, http.StatusUnprocessableEntity, status)
	default:
		writeJSON(w, http.StatusAccepted, status)
	}
}

func deviceBearerToken(w http.ResponseWriter, r *http.Request) (string, bool) {
	scheme, token, ok := strings.Cut(r.Header.Get("Authorization"), " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") || token == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "device_token_required"})
		return "", false
	}
	return token, true
}

func (s *Server) writeStravaError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, nakvalistrava.ErrInvalidDeviceToken):
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid_device_token"})
	case errors.Is(err, nakvalistrava.ErrNotConnected),
		errors.Is(err, store.ErrStravaConnectionNotFound):
		writeJSON(w, http.StatusConflict, map[string]string{"error": "strava_not_connected"})
	case errors.Is(err, nakvalistrava.ErrWriteScopeMissing):
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "strava_write_scope_required"})
	default:
		s.logger.Error("strava request failed", "error", err)
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "strava_unavailable"})
	}
}
