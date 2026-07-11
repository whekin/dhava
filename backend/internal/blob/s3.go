package blob

import (
	"context"
	"fmt"
	"io"
	"net/url"
	"strings"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// S3Store stores objects in an S3-compatible bucket (AWS S3, MinIO, ...).
type S3Store struct {
	client *minio.Client
	bucket string
}

// S3Config holds connection parameters for an S3-compatible endpoint.
type S3Config struct {
	// Endpoint is the S3 host, optionally with a scheme
	// (e.g. "minio:9000", "http://localhost:9000", "https://s3.example.com").
	Endpoint  string
	Bucket    string
	AccessKey string
	SecretKey string
}

// NewS3 creates an S3-backed store and ensures the bucket exists,
// creating it when missing.
func NewS3(ctx context.Context, cfg S3Config) (*S3Store, error) {
	endpoint, secure, err := parseEndpoint(cfg.Endpoint)
	if err != nil {
		return nil, err
	}

	client, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, ""),
		Secure: secure,
	})
	if err != nil {
		return nil, fmt.Errorf("create s3 client: %w", err)
	}

	exists, err := client.BucketExists(ctx, cfg.Bucket)
	if err != nil {
		return nil, fmt.Errorf("check bucket %q: %w", cfg.Bucket, err)
	}
	if !exists {
		if err := client.MakeBucket(ctx, cfg.Bucket, minio.MakeBucketOptions{}); err != nil {
			return nil, fmt.Errorf("create bucket %q: %w", cfg.Bucket, err)
		}
	}

	return &S3Store{client: client, bucket: cfg.Bucket}, nil
}

// Put streams the object to the bucket. size may be -1 when unknown;
// the client then falls back to multipart streaming upload.
func (s *S3Store) Put(ctx context.Context, key string, r io.Reader, size int64, contentType string) error {
	opts := minio.PutObjectOptions{ContentType: contentType}
	if size < 0 {
		// Without a known size minio-go picks a very large multipart buffer;
		// cap it so unknown-length streams don't blow up memory.
		opts.PartSize = 16 << 20
	}
	_, err := s.client.PutObject(ctx, s.bucket, key, r, size, opts)
	if err != nil {
		return fmt.Errorf("put object %q: %w", key, err)
	}
	return nil
}

// parseEndpoint splits an optional scheme off the endpoint and maps it to
// the minio "secure" flag. Without a scheme, TLS is assumed.
func parseEndpoint(endpoint string) (host string, secure bool, err error) {
	if !strings.Contains(endpoint, "://") {
		return endpoint, true, nil
	}
	u, err := url.Parse(endpoint)
	if err != nil {
		return "", false, fmt.Errorf("parse s3 endpoint: %w", err)
	}
	switch u.Scheme {
	case "http":
		secure = false
	case "https":
		secure = true
	default:
		return "", false, fmt.Errorf("unsupported s3 endpoint scheme %q", u.Scheme)
	}
	return u.Host, secure, nil
}
