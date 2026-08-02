package api

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/whekin/nakvali/backend/internal/identity"
	"github.com/whekin/nakvali/backend/internal/store"
)

type fakeIdentityVerifier struct {
	token string
	user  identity.User
	err   error
}

func (f *fakeIdentityVerifier) Verify(_ context.Context, token string) (identity.User, error) {
	f.token = token
	return f.user, f.err
}

type fakeAuthDatastore struct {
	identity store.FirebaseIdentity
	user     store.User
	err      error
}

func (f *fakeAuthDatastore) UpsertFirebaseUser(
	_ context.Context,
	identity store.FirebaseIdentity,
) (store.User, error) {
	f.identity = identity
	return f.user, f.err
}

func (*fakeAuthDatastore) CreateActivity(context.Context, string, time.Time) (string, error) {
	panic("unexpected CreateActivity call")
}

func (*fakeAuthDatastore) ActivityExists(context.Context, string) (bool, error) {
	panic("unexpected ActivityExists call")
}

func (*fakeAuthDatastore) AttachRawRecording(context.Context, string, string, string, int64) error {
	panic("unexpected AttachRawRecording call")
}

func (*fakeAuthDatastore) FinishActivity(
	context.Context,
	string,
	time.Time,
	store.ActivityMetadata,
) (bool, error) {
	panic("unexpected FinishActivity call")
}

func authTestRouter(db Datastore, verifier identity.Verifier) http.Handler {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return newRouterWithOptions(logger, nil, db, nil, WithIdentityVerifier(verifier))
}

func TestMeVerifiesTokenAndUpsertsLocalUser(t *testing.T) {
	verifier := &fakeIdentityVerifier{user: identity.User{
		UID:           "firebase-uid",
		Email:         "rider@example.com",
		DisplayName:   "Trail Rider",
		AvatarURL:     "https://example.com/avatar.jpg",
		EmailVerified: true,
	}}
	db := &fakeAuthDatastore{user: store.User{
		ID:            "5b0ce8d8-978e-41cd-b657-4a0c412d6c30",
		Email:         "rider@example.com",
		DisplayName:   "Trail Rider",
		AvatarURL:     "https://example.com/avatar.jpg",
		EmailVerified: true,
	}}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/api/v1/me", nil)
	request.Header.Set("Authorization", "Bearer signed-firebase-token")

	authTestRouter(db, verifier).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body)
	}
	if verifier.token != "signed-firebase-token" {
		t.Fatalf("verified token = %q", verifier.token)
	}
	if db.identity.UID != "firebase-uid" || db.identity.Email != "rider@example.com" {
		t.Fatalf("upserted identity = %+v", db.identity)
	}
	if body := recorder.Body.String(); body == "" || !containsAll(
		body,
		`"id":"5b0ce8d8-978e-41cd-b657-4a0c412d6c30"`,
		`"display_name":"Trail Rider"`,
		`"email_verified":true`,
	) {
		t.Fatalf("unexpected body: %s", body)
	}
}

func TestMeRejectsMissingAndInvalidTokens(t *testing.T) {
	for _, test := range []struct {
		name   string
		header string
		err    error
	}{
		{name: "missing"},
		{name: "wrong scheme", header: "Basic abc"},
		{name: "invalid", header: "Bearer invalid", err: errors.New("invalid token")},
	} {
		t.Run(test.name, func(t *testing.T) {
			verifier := &fakeIdentityVerifier{err: test.err}
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, "/api/v1/me", nil)
			if test.header != "" {
				request.Header.Set("Authorization", test.header)
			}

			authTestRouter(&fakeAuthDatastore{}, verifier).ServeHTTP(recorder, request)

			if recorder.Code != http.StatusUnauthorized {
				t.Fatalf("status = %d, want 401", recorder.Code)
			}
		})
	}
}

func TestMeReportsDatabaseUnavailableAfterValidIdentity(t *testing.T) {
	verifier := &fakeIdentityVerifier{user: identity.User{UID: "firebase-uid"}}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/api/v1/me", nil)
	request.Header.Set("Authorization", "Bearer valid")

	authTestRouter(nil, verifier).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", recorder.Code)
	}
}

func containsAll(value string, parts ...string) bool {
	for _, part := range parts {
		if !strings.Contains(value, part) {
			return false
		}
	}
	return true
}
