package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
)

// Datastore is the subset of database operations the handlers need.
// It is an interface so handlers can be tested without a live database.
type Datastore interface {
	CreateActivity(ctx context.Context, sport string, startedAt time.Time) (string, error)
	ActivityExists(ctx context.Context, id string) (bool, error)
	AttachRawRecording(ctx context.Context, activityID, storageKey, format string, sizeBytes int64) error
	FinishActivity(ctx context.Context, id string, endedAt time.Time) (bool, error)
}

const (
	rawContentType = "application/gzip"
	rawFormat      = "jsonl.gz"
)

type createActivityRequest struct {
	Sport       string `json:"sport"`
	StartedAtMs int64  `json:"started_at_ms"`
}

// handleCreateActivity creates a new activity in status "recording".
func (s *Server) handleCreateActivity(w http.ResponseWriter, r *http.Request) {
	var req createActivityRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		return
	}
	if req.Sport == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "sport_required"})
		return
	}
	if req.StartedAtMs <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "started_at_ms_required"})
		return
	}

	if s.db == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "db_unavailable"})
		return
	}

	id, err := s.db.CreateActivity(r.Context(), req.Sport, time.UnixMilli(req.StartedAtMs).UTC())
	if err != nil {
		s.logger.Error("create activity failed", "error", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "db_unavailable"})
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"id": id})
}

// handleUploadRaw streams the gzipped raw recording to object storage and
// records it in the database.
func (s *Server) handleUploadRaw(w http.ResponseWriter, r *http.Request) {
	id, ok := activityID(w, r)
	if !ok {
		return
	}

	if ct, _, err := mime.ParseMediaType(r.Header.Get("Content-Type")); err != nil || ct != rawContentType {
		writeJSON(w, http.StatusUnsupportedMediaType, map[string]string{"error": "content_type_must_be_application_gzip"})
		return
	}
	if r.ContentLength == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "empty_body"})
		return
	}
	if r.ContentLength > s.maxRawBodyBytes {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "body_too_large"})
		return
	}

	if s.db == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "db_unavailable"})
		return
	}

	exists, err := s.db.ActivityExists(r.Context(), id)
	if err != nil {
		s.logger.Error("activity lookup failed", "error", err, "activity_id", id)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "db_unavailable"})
		return
	}
	if !exists {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "activity_not_found"})
		return
	}

	// Stream the body to object storage without buffering it in memory.
	// MaxBytesReader enforces the size cap even without a Content-Length.
	body := http.MaxBytesReader(w, r.Body, s.maxRawBodyBytes)
	counted := &countingReader{r: body}
	key := fmt.Sprintf("raw-recordings/%s.jsonl.gz", id)

	if err := s.blobs.Put(r.Context(), key, counted, r.ContentLength, rawContentType); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "body_too_large"})
			return
		}
		s.logger.Error("blob upload failed", "error", err, "key", key)
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "storage_error"})
		return
	}

	if err := s.db.AttachRawRecording(r.Context(), id, key, rawFormat, counted.n); err != nil {
		s.logger.Error("attach raw recording failed", "error", err, "activity_id", id)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "db_unavailable"})
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type finishActivityRequest struct {
	EndedAtMs int64 `json:"ended_at_ms"`
}

// handleFinishActivity marks the activity as fully uploaded.
func (s *Server) handleFinishActivity(w http.ResponseWriter, r *http.Request) {
	id, ok := activityID(w, r)
	if !ok {
		return
	}

	var req finishActivityRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_json"})
		return
	}
	if req.EndedAtMs <= 0 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "ended_at_ms_required"})
		return
	}

	if s.db == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "db_unavailable"})
		return
	}

	found, err := s.db.FinishActivity(r.Context(), id, time.UnixMilli(req.EndedAtMs).UTC())
	if err != nil {
		s.logger.Error("finish activity failed", "error", err, "activity_id", id)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "db_unavailable"})
		return
	}
	if !found {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "activity_not_found"})
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// activityID extracts and validates the {id} URL parameter. On failure it
// writes an error response and returns ok=false.
func activityID(w http.ResponseWriter, r *http.Request) (string, bool) {
	id := chi.URLParam(r, "id")
	if _, err := uuid.Parse(id); err != nil {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "activity_not_found"})
		return "", false
	}
	return id, true
}

// countingReader counts bytes read through it, so the actual uploaded size
// is known even when the request has no Content-Length.
type countingReader struct {
	r io.Reader
	n int64
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n += int64(n)
	return n, err
}
