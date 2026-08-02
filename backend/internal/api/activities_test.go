package api

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/whekin/nakvali/backend/internal/store"
)

const testActivityID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

// fakeDatastore is an in-memory Datastore for handler tests.
type fakeDatastore struct {
	activities map[string]bool
	finished   map[string]time.Time
	meta       map[string]store.ActivityMetadata
	rawKey     string
	rawSize    int64
}

func (f *fakeDatastore) UpsertFirebaseUser(
	context.Context,
	store.FirebaseIdentity,
) (store.User, error) {
	panic("unexpected UpsertFirebaseUser call")
}

func newFakeDatastore() *fakeDatastore {
	return &fakeDatastore{
		activities: map[string]bool{},
		finished:   map[string]time.Time{},
		meta:       map[string]store.ActivityMetadata{},
	}
}

func (f *fakeDatastore) CreateActivity(ctx context.Context, sport string, startedAt time.Time) (string, error) {
	f.activities[testActivityID] = true
	return testActivityID, nil
}

func (f *fakeDatastore) ActivityExists(ctx context.Context, id string) (bool, error) {
	return f.activities[id], nil
}

func (f *fakeDatastore) AttachRawRecording(ctx context.Context, activityID, storageKey, format string, sizeBytes int64) error {
	f.rawKey = storageKey
	f.rawSize = sizeBytes
	return nil
}

func (f *fakeDatastore) FinishActivity(ctx context.Context, id string, endedAt time.Time, meta store.ActivityMetadata) (bool, error) {
	if !f.activities[id] {
		return false, nil
	}
	f.finished[id] = endedAt
	f.meta[id] = meta
	return true, nil
}

// fakeBlobStore captures Put calls in memory.
type fakeBlobStore struct {
	key  string
	data []byte
	err  error
}

func (f *fakeBlobStore) Put(ctx context.Context, key string, r io.Reader, size int64, contentType string) error {
	data, err := io.ReadAll(r)
	if err != nil {
		return err
	}
	if f.err != nil {
		return f.err
	}
	f.key = key
	f.data = data
	return nil
}

func newTestServer(t *testing.T, db Datastore, blobs *fakeBlobStore) http.Handler {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return newRouter(logger, nil, db, blobs)
}

func gzipBytes(t *testing.T, payload string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	if _, err := zw.Write([]byte(payload)); err != nil {
		t.Fatalf("gzip write: %v", err)
	}
	if err := zw.Close(); err != nil {
		t.Fatalf("gzip close: %v", err)
	}
	return buf.Bytes()
}

func TestCreateActivity(t *testing.T) {
	db := newFakeDatastore()
	h := newTestServer(t, db, &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/activities",
		strings.NewReader(`{"sport":"downhill","started_at_ms":1770000000000}`))
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d; body: %s", rec.Code, http.StatusCreated, rec.Body)
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("invalid JSON body: %v", err)
	}
	if body["id"] != testActivityID {
		t.Errorf("id = %q, want %q", body["id"], testActivityID)
	}
}

func TestCreateActivityValidation(t *testing.T) {
	tests := []struct {
		name string
		body string
	}{
		{"invalid json", `not json`},
		{"missing sport", `{"started_at_ms":1770000000000}`},
		{"empty sport", `{"sport":"","started_at_ms":1770000000000}`},
		{"missing started_at_ms", `{"sport":"downhill"}`},
		{"negative started_at_ms", `{"sport":"downhill","started_at_ms":-1}`},
	}
	h := newTestServer(t, newFakeDatastore(), &fakeBlobStore{})
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, "/api/v1/activities", strings.NewReader(tt.body))
			h.ServeHTTP(rec, req)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want %d; body: %s", rec.Code, http.StatusBadRequest, rec.Body)
			}
		})
	}
}

func TestCreateActivityWithoutDatabase(t *testing.T) {
	h := newTestServer(t, nil, &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/activities",
		strings.NewReader(`{"sport":"downhill","started_at_ms":1770000000000}`))
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("invalid JSON body: %v", err)
	}
	if body["error"] != "db_unavailable" {
		t.Errorf("error = %q, want %q", body["error"], "db_unavailable")
	}
}

func TestUploadRaw(t *testing.T) {
	db := newFakeDatastore()
	db.activities[testActivityID] = true
	blobs := &fakeBlobStore{}
	h := newTestServer(t, db, blobs)

	payload := gzipBytes(t, `{"type":"meta","version":1}`)
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPut, "/api/v1/activities/"+testActivityID+"/raw", bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/gzip")
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d; body: %s", rec.Code, http.StatusNoContent, rec.Body)
	}
	wantKey := "raw-recordings/" + testActivityID + ".jsonl.gz"
	if blobs.key != wantKey {
		t.Errorf("blob key = %q, want %q", blobs.key, wantKey)
	}
	if !bytes.Equal(blobs.data, payload) {
		t.Errorf("blob data mismatch: got %d bytes, want %d", len(blobs.data), len(payload))
	}
	if db.rawKey != wantKey {
		t.Errorf("db storage_key = %q, want %q", db.rawKey, wantKey)
	}
	if db.rawSize != int64(len(payload)) {
		t.Errorf("db size_bytes = %d, want %d", db.rawSize, len(payload))
	}
}

func TestUploadRawWrongContentType(t *testing.T) {
	db := newFakeDatastore()
	db.activities[testActivityID] = true
	h := newTestServer(t, db, &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPut, "/api/v1/activities/"+testActivityID+"/raw",
		bytes.NewReader(gzipBytes(t, "x")))
	req.Header.Set("Content-Type", "application/octet-stream")
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnsupportedMediaType)
	}
}

func TestUploadRawTooLarge(t *testing.T) {
	db := newFakeDatastore()
	db.activities[testActivityID] = true
	h := newTestServer(t, db, &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPut, "/api/v1/activities/"+testActivityID+"/raw",
		bytes.NewReader(gzipBytes(t, "x")))
	req.Header.Set("Content-Type", "application/gzip")
	req.ContentLength = maxRawBodyBytes + 1 // declared size over the cap
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusRequestEntityTooLarge)
	}
}

func TestUploadRawUnknownActivity(t *testing.T) {
	h := newTestServer(t, newFakeDatastore(), &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPut, "/api/v1/activities/"+testActivityID+"/raw",
		bytes.NewReader(gzipBytes(t, "x")))
	req.Header.Set("Content-Type", "application/gzip")
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNotFound)
	}
}

func TestUploadRawInvalidID(t *testing.T) {
	h := newTestServer(t, newFakeDatastore(), &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPut, "/api/v1/activities/not-a-uuid/raw",
		bytes.NewReader(gzipBytes(t, "x")))
	req.Header.Set("Content-Type", "application/gzip")
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNotFound)
	}
}

func TestUploadRawWithoutDatabase(t *testing.T) {
	h := newTestServer(t, nil, &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPut, "/api/v1/activities/"+testActivityID+"/raw",
		bytes.NewReader(gzipBytes(t, "x")))
	req.Header.Set("Content-Type", "application/gzip")
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
	}
}

func TestFinishActivity(t *testing.T) {
	db := newFakeDatastore()
	db.activities[testActivityID] = true
	h := newTestServer(t, db, &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/activities/"+testActivityID+"/finish",
		strings.NewReader(`{"ended_at_ms":1770000600000}`))
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d; body: %s", rec.Code, http.StatusNoContent, rec.Body)
	}
	want := time.UnixMilli(1770000600000).UTC()
	if got := db.finished[testActivityID]; !got.Equal(want) {
		t.Errorf("ended_at = %v, want %v", got, want)
	}
	// The client omits empty metadata fields; all must come through as nil.
	meta := db.meta[testActivityID]
	if meta.Title != nil || meta.Description != nil || meta.Bike != nil || meta.BikeType != nil {
		t.Errorf("metadata = %+v, want all nil", meta)
	}
}

func TestFinishActivityWithMetadata(t *testing.T) {
	db := newFakeDatastore()
	db.activities[testActivityID] = true
	h := newTestServer(t, db, &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/activities/"+testActivityID+"/finish",
		strings.NewReader(`{"ended_at_ms":1770000600000,"title":"Morning laps","description":"3 runs, dusty","bike":"Norco Range","bike_type":"full_sus"}`))
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d; body: %s", rec.Code, http.StatusNoContent, rec.Body)
	}
	meta := db.meta[testActivityID]
	checks := []struct {
		name string
		got  *string
		want string
	}{
		{"title", meta.Title, "Morning laps"},
		{"description", meta.Description, "3 runs, dusty"},
		{"bike", meta.Bike, "Norco Range"},
		{"bike_type", meta.BikeType, "full_sus"},
	}
	for _, c := range checks {
		if c.got == nil || *c.got != c.want {
			t.Errorf("%s = %v, want %q", c.name, c.got, c.want)
		}
	}
}

func TestFinishActivityEmptyMetadataFields(t *testing.T) {
	db := newFakeDatastore()
	db.activities[testActivityID] = true
	h := newTestServer(t, db, &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/activities/"+testActivityID+"/finish",
		strings.NewReader(`{"ended_at_ms":1770000600000,"title":"","description":"","bike":"","bike_type":""}`))
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d; body: %s", rec.Code, http.StatusNoContent, rec.Body)
	}
	meta := db.meta[testActivityID]
	if meta.Title != nil || meta.Description != nil || meta.Bike != nil || meta.BikeType != nil {
		t.Errorf("metadata = %+v, want all nil", meta)
	}
}

func TestFinishActivityValidation(t *testing.T) {
	db := newFakeDatastore()
	db.activities[testActivityID] = true
	h := newTestServer(t, db, &fakeBlobStore{})

	longField := func(n int) string { return strings.Repeat("x", n) }
	tests := []struct {
		name      string
		body      string
		wantError string
	}{
		{"invalid json", `not json`, "invalid_json"},
		{"empty object", `{}`, "ended_at_ms_required"},
		{"zero ended_at_ms", `{"ended_at_ms":0}`, "ended_at_ms_required"},
		{"invalid bike_type", `{"ended_at_ms":1770000600000,"bike_type":"unicycle"}`, "invalid_bike_type"},
		{"title too long", `{"ended_at_ms":1770000600000,"title":"` + longField(201) + `"}`, "title_too_long"},
		{"description too long", `{"ended_at_ms":1770000600000,"description":"` + longField(5001) + `"}`, "description_too_long"},
		{"bike too long", `{"ended_at_ms":1770000600000,"bike":"` + longField(101) + `"}`, "bike_too_long"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, "/api/v1/activities/"+testActivityID+"/finish",
				strings.NewReader(tt.body))
			h.ServeHTTP(rec, req)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, want %d; body: %s", rec.Code, http.StatusBadRequest, rec.Body)
			}
			var body map[string]string
			if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
				t.Fatalf("invalid JSON body: %v", err)
			}
			if body["error"] != tt.wantError {
				t.Errorf("error = %q, want %q", body["error"], tt.wantError)
			}
		})
	}
}

func TestFinishActivityMaxLengthMetadataAccepted(t *testing.T) {
	db := newFakeDatastore()
	db.activities[testActivityID] = true
	h := newTestServer(t, db, &fakeBlobStore{})

	body := `{"ended_at_ms":1770000600000,` +
		`"title":"` + strings.Repeat("x", 200) + `",` +
		`"description":"` + strings.Repeat("x", 5000) + `",` +
		`"bike":"` + strings.Repeat("x", 100) + `"}`
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/activities/"+testActivityID+"/finish",
		strings.NewReader(body))
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d; body: %s", rec.Code, http.StatusNoContent, rec.Body)
	}
}

func TestFinishActivityUnknown(t *testing.T) {
	h := newTestServer(t, newFakeDatastore(), &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/activities/"+testActivityID+"/finish",
		strings.NewReader(`{"ended_at_ms":1770000600000}`))
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNotFound)
	}
}

func TestFinishActivityWithoutDatabase(t *testing.T) {
	h := newTestServer(t, nil, &fakeBlobStore{})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/activities/"+testActivityID+"/finish",
		strings.NewReader(`{"ended_at_ms":1770000600000}`))
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusServiceUnavailable)
	}
}
