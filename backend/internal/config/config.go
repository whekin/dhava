// Package config provides environment-based configuration for the Dhava backend.
package config

import (
	"log/slog"
	"os"
	"strconv"
	"strings"
)

// Config holds all runtime configuration, loaded from environment variables.
type Config struct {
	// Port is the TCP port the HTTP server listens on.
	Port string
	// DatabaseURL is the PostgreSQL connection string (pgx format).
	DatabaseURL string
	// LogLevel is one of: debug, info, warn, error.
	LogLevel string
	// APIAccessKey gates the private-alpha API through X-Dhava-Access-Key.
	// Empty keeps local development open; production deployment requires it.
	APIAccessKey string
	// RawUploadsEnabled retains the legacy raw-ingestion endpoints for explicit
	// development use. Product deployments keep raw sensor data on-device.
	RawUploadsEnabled bool
	// S3Endpoint is the S3/MinIO endpoint, optionally with scheme
	// (e.g. "http://localhost:9000"). Empty means: use filesystem blob storage.
	S3Endpoint string
	// S3Bucket is the bucket for raw recordings.
	S3Bucket string
	// S3AccessKey and S3SecretKey are the S3 credentials.
	S3AccessKey string
	S3SecretKey string
	// BlobDir is the base directory for the filesystem blob store,
	// used when S3Endpoint is unset.
	BlobDir string
	// PublicBaseURL is the externally reachable origin used for OAuth callbacks,
	// for example https://api.dhava.app. http://127.0.0.1:8080 is also valid
	// for an attached-device development flow using adb reverse.
	PublicBaseURL string
	// StravaClientID and StravaClientSecret come from the Strava API dashboard.
	// The secret must never be shipped in an Android build.
	StravaClientID     string
	StravaClientSecret string
	// StravaAppRedirectURL returns the browser to Dhava after the server has
	// exchanged the one-time authorization code.
	StravaAppRedirectURL string
}

// Load reads configuration from the environment, applying defaults.
func Load() Config {
	return Config{
		Port:               getenv("PORT", "8080"),
		DatabaseURL:        getenv("DATABASE_URL", ""),
		LogLevel:           getenv("LOG_LEVEL", "info"),
		APIAccessKey:       strings.TrimSpace(getenv("API_ACCESS_KEY", "")),
		RawUploadsEnabled:  getenvBool("RAW_UPLOADS_ENABLED", false),
		S3Endpoint:         getenv("S3_ENDPOINT", ""),
		S3Bucket:           getenv("S3_BUCKET", "dhava"),
		S3AccessKey:        getenv("S3_ACCESS_KEY", ""),
		S3SecretKey:        getenv("S3_SECRET_KEY", ""),
		BlobDir:            getenv("BLOB_DIR", "./data/blobs"),
		PublicBaseURL:      strings.TrimRight(getenv("PUBLIC_BASE_URL", ""), "/"),
		StravaClientID:     getenv("STRAVA_CLIENT_ID", ""),
		StravaClientSecret: getenv("STRAVA_CLIENT_SECRET", ""),
		StravaAppRedirectURL: getenv(
			"STRAVA_APP_REDIRECT_URL",
			"dhava://strava/connected",
		),
	}
}

// StravaConfigured reports whether the broker has every value needed for a
// real OAuth exchange. Routes remain available and return a clear 503 when it
// is false, which keeps local recorder development backend-optional.
func (c Config) StravaConfigured() bool {
	return c.PublicBaseURL != "" && c.StravaClientID != "" && c.StravaClientSecret != ""
}

// SlogLevel maps the configured log level string to a slog.Level.
func (c Config) SlogLevel() slog.Level {
	switch strings.ToLower(c.LogLevel) {
	case "debug":
		return slog.LevelDebug
	case "warn", "warning":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getenvBool(key string, fallback bool) bool {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}
