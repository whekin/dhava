// Package blob abstracts object storage for raw recording uploads.
// Two implementations exist: S3/MinIO (production) and local filesystem
// (development fallback when S3 is not configured).
package blob

import (
	"context"
	"io"
)

// Store writes objects to a storage backend.
type Store interface {
	// Put streams r to the object identified by key. size is the expected
	// number of bytes, or -1 when unknown.
	Put(ctx context.Context, key string, r io.Reader, size int64, contentType string) error
}
