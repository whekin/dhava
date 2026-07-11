package blob

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// FSStore stores objects as files under a base directory.
// It is the development fallback when S3 is not configured.
type FSStore struct {
	dir string
}

// NewFS creates a filesystem-backed store rooted at dir.
func NewFS(dir string) *FSStore {
	return &FSStore{dir: dir}
}

// Put writes the object to <dir>/<key>, creating parent directories as
// needed. The write goes through a temporary file and is renamed into place
// so readers never observe partial objects.
func (s *FSStore) Put(ctx context.Context, key string, r io.Reader, size int64, contentType string) error {
	path := filepath.Join(s.dir, filepath.FromSlash(key))
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return fmt.Errorf("create blob dir: %w", err)
	}

	tmp, err := os.CreateTemp(filepath.Dir(path), filepath.Base(path)+".tmp-*")
	if err != nil {
		return fmt.Errorf("create temp blob: %w", err)
	}
	defer func() {
		tmp.Close()
		os.Remove(tmp.Name())
	}()

	if _, err := io.Copy(tmp, r); err != nil {
		return fmt.Errorf("write blob: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close blob: %w", err)
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		return fmt.Errorf("finalize blob: %w", err)
	}
	return nil
}
