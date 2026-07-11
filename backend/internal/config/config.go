// Package config provides environment-based configuration for the Dhava backend.
package config

import (
	"log/slog"
	"os"
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
}

// Load reads configuration from the environment, applying defaults.
func Load() Config {
	return Config{
		Port:        getenv("PORT", "8080"),
		DatabaseURL: getenv("DATABASE_URL", ""),
		LogLevel:    getenv("LOG_LEVEL", "info"),
		S3Endpoint:  getenv("S3_ENDPOINT", ""),
		S3Bucket:    getenv("S3_BUCKET", "dhava"),
		S3AccessKey: getenv("S3_ACCESS_KEY", ""),
		S3SecretKey: getenv("S3_SECRET_KEY", ""),
		BlobDir:     getenv("BLOB_DIR", "./data/blobs"),
	}
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
