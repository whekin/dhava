// Package api contains the HTTP router, middleware, and handlers.
package api

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/whekin/nakvali/backend/internal/blob"
	"github.com/whekin/nakvali/backend/internal/identity"
	"github.com/whekin/nakvali/backend/internal/store"
	nakvalistrava "github.com/whekin/nakvali/backend/internal/strava"
)

// maxRawBodyBytes caps raw recording uploads at 256 MB.
const maxRawBodyBytes = 256 << 20

// Server bundles handler dependencies.
type Server struct {
	logger          *slog.Logger
	pool            *pgxpool.Pool // may be nil if the database is not configured/reachable
	db              Datastore     // nil when the pool is nil
	blobs           blob.Store
	maxRawBodyBytes int64
	strava          StravaBroker
	identity        identity.Verifier
	accessKey       string
	rawUploads      bool
}

// StravaBroker is the OAuth/upload behavior exposed through the HTTP API.
// The interface keeps handler tests independent of PostgreSQL and Strava.
type StravaBroker interface {
	BeginConnect(context.Context, string) (nakvalistrava.ConnectStart, error)
	CompleteConnect(context.Context, string, string, string) error
	AppRedirectURL(string) string
	Connection(context.Context, string) (nakvalistrava.ConnectionStatus, error)
	Export(context.Context, string, nakvalistrava.ExportRequest) (nakvalistrava.ExportStatus, error)
}

type RouterOption func(*Server)

func WithStravaBroker(broker StravaBroker) RouterOption {
	return func(server *Server) {
		server.strava = broker
	}
}

// WithIdentityVerifier enables Firebase ID-token authentication for user routes.
func WithIdentityVerifier(verifier identity.Verifier) RouterOption {
	return func(server *Server) {
		server.identity = verifier
	}
}

// WithAccessKey protects private-alpha API routes with X-Nakvali-Access-Key.
// Health/readiness and the browser-facing Strava callback remain public.
func WithAccessKey(key string) RouterOption {
	return func(server *Server) {
		server.accessKey = key
	}
}

// WithRawUploadsEnabled exposes the legacy activity/raw ingestion contract.
// Product deployments intentionally omit it because raw sensor data stays on-device.
func WithRawUploadsEnabled(enabled bool) RouterOption {
	return func(server *Server) {
		server.rawUploads = enabled
	}
}

// NewRouter builds the HTTP handler with all middleware and routes.
// pool may be nil; in that case /readyz reports the service as not ready
// and database-backed endpoints respond 503.
func NewRouter(
	logger *slog.Logger,
	pool *pgxpool.Pool,
	blobs blob.Store,
	options ...RouterOption,
) http.Handler {
	var db Datastore
	if pool != nil {
		db = store.New(pool)
	}
	return newRouterWithOptions(logger, pool, db, blobs, options...)
}

// newRouter is the test seam: it accepts the Datastore interface directly.
func newRouter(logger *slog.Logger, pool *pgxpool.Pool, db Datastore, blobs blob.Store) http.Handler {
	return newRouterWithOptions(logger, pool, db, blobs, WithRawUploadsEnabled(true))
}

func newRouterWithOptions(
	logger *slog.Logger,
	pool *pgxpool.Pool,
	db Datastore,
	blobs blob.Store,
	options ...RouterOption,
) http.Handler {
	s := &Server{
		logger:          logger,
		pool:            pool,
		db:              db,
		blobs:           blobs,
		maxRawBodyBytes: maxRawBodyBytes,
	}
	for _, option := range options {
		option(s)
	}

	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(requestLogger(logger))
	r.Use(middleware.Recoverer)

	r.Get("/healthz", s.handleHealthz)
	r.Get("/readyz", s.handleReadyz)

	r.Route("/api/v1", func(r chi.Router) {
		// The browser returns here from Strava and cannot carry the private app header.
		r.Get("/strava/oauth/callback", s.handleStravaOAuthCallback)

		r.Group(func(r chi.Router) {
			r.Use(s.requireAccessKey)
			r.With(s.requireFirebaseIdentity).Get("/me", s.handleMe)
			if s.rawUploads {
				r.Post("/activities", s.handleCreateActivity)
				r.Put("/activities/{id}/raw", s.handleUploadRaw)
				r.Post("/activities/{id}/finish", s.handleFinishActivity)
			}
			r.Post("/strava/connect", s.handleBeginStravaConnect)
			r.Get("/strava/connection", s.handleStravaConnection)
			r.Post("/strava/exports", s.handleStravaExport)
		})
	})

	return r
}

func (s *Server) requireAccessKey(next http.Handler) http.Handler {
	if s.accessKey == "" {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		provided := r.Header.Get("X-Nakvali-Access-Key")
		if subtle.ConstantTimeCompare([]byte(provided), []byte(s.accessKey)) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "access_key_required"})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) handleHealthz(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) handleReadyz(w http.ResponseWriter, r *http.Request) {
	if s.pool == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable", "reason": "database not configured"})
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()

	if err := s.pool.Ping(ctx); err != nil {
		s.logger.Warn("readiness check failed", "error", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable", "reason": "database unreachable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
