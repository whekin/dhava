// Command api runs the Dhava backend HTTP server.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/whekin/dhava/backend/internal/api"
	"github.com/whekin/dhava/backend/internal/blob"
	"github.com/whekin/dhava/backend/internal/config"
	"github.com/whekin/dhava/backend/internal/store"
	dhavastrava "github.com/whekin/dhava/backend/internal/strava"
)

func main() {
	cfg := config.Load()

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: cfg.SlogLevel(),
	}))
	slog.SetDefault(logger)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	// Connect the database pool lazily: the server must start and serve
	// /healthz even if the database is unreachable or not configured.
	var pool *pgxpool.Pool
	if cfg.DatabaseURL == "" {
		logger.Warn("DATABASE_URL not set; starting without database")
	} else {
		p, err := store.NewPool(ctx, cfg.DatabaseURL)
		if err != nil {
			logger.Warn("failed to initialize database pool; starting without database", "error", err)
		} else {
			pool = p
			defer pool.Close()
		}
	}

	// Object storage: S3/MinIO when configured, filesystem fallback otherwise.
	var blobs blob.Store
	if cfg.S3Endpoint != "" {
		s3, err := blob.NewS3(ctx, blob.S3Config{
			Endpoint:  cfg.S3Endpoint,
			Bucket:    cfg.S3Bucket,
			AccessKey: cfg.S3AccessKey,
			SecretKey: cfg.S3SecretKey,
		})
		if err != nil {
			logger.Error("failed to initialize s3 blob store", "error", err, "endpoint", cfg.S3Endpoint)
			os.Exit(1)
		}
		blobs = s3
		logger.Info("blob storage: s3", "endpoint", cfg.S3Endpoint, "bucket", cfg.S3Bucket)
	} else {
		blobs = blob.NewFS(cfg.BlobDir)
		logger.Info("blob storage: filesystem", "dir", cfg.BlobDir)
	}

	var routerOptions []api.RouterOption
	if cfg.StravaConfigured() && pool != nil {
		stravaClient := dhavastrava.NewClient(
			&http.Client{Timeout: 30 * time.Second},
			cfg.StravaClientID,
			cfg.StravaClientSecret,
		)
		stravaService := dhavastrava.NewService(
			store.New(pool),
			stravaClient,
			dhavastrava.Config{
				ClientID:       cfg.StravaClientID,
				PublicBaseURL:  cfg.PublicBaseURL,
				AppRedirectURL: cfg.StravaAppRedirectURL,
			},
		)
		routerOptions = append(routerOptions, api.WithStravaBroker(stravaService))
		logger.Info("strava broker enabled", "callback_origin", cfg.PublicBaseURL)
	} else {
		logger.Warn(
			"strava broker disabled; requires database and Strava configuration",
			"database_configured",
			pool != nil,
			"strava_configured",
			cfg.StravaConfigured(),
		)
	}

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           api.NewRouter(logger, pool, blobs, routerOptions...),
		ReadHeaderTimeout: 5 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("http server listening", "addr", srv.Addr)
		errCh <- srv.ListenAndServe()
	}()

	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received")
	case err := <-errCh:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http server failed", "error", err)
			os.Exit(1)
		}
		return
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("graceful shutdown failed", "error", err)
		os.Exit(1)
	}
	logger.Info("server stopped")
}
