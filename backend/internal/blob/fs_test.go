package blob

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestFSStorePut(t *testing.T) {
	dir := t.TempDir()
	s := NewFS(dir)

	data := []byte("hello blob")
	key := "raw-recordings/abc.jsonl.gz"
	if err := s.Put(context.Background(), key, bytes.NewReader(data), int64(len(data)), "application/gzip"); err != nil {
		t.Fatalf("Put: %v", err)
	}

	got, err := os.ReadFile(filepath.Join(dir, "raw-recordings", "abc.jsonl.gz"))
	if err != nil {
		t.Fatalf("read stored blob: %v", err)
	}
	if !bytes.Equal(got, data) {
		t.Errorf("stored data = %q, want %q", got, data)
	}

	// No leftover temp files.
	entries, err := os.ReadDir(filepath.Join(dir, "raw-recordings"))
	if err != nil {
		t.Fatalf("read dir: %v", err)
	}
	if len(entries) != 1 {
		t.Errorf("dir has %d entries, want 1", len(entries))
	}
}

func TestParseEndpoint(t *testing.T) {
	tests := []struct {
		in     string
		host   string
		secure bool
		errOK  bool
	}{
		{"minio:9000", "minio:9000", true, false},
		{"http://localhost:9000", "localhost:9000", false, false},
		{"https://s3.example.com", "s3.example.com", true, false},
		{"ftp://x", "", false, true},
	}
	for _, tt := range tests {
		host, secure, err := parseEndpoint(tt.in)
		if tt.errOK {
			if err == nil {
				t.Errorf("parseEndpoint(%q): expected error", tt.in)
			}
			continue
		}
		if err != nil {
			t.Errorf("parseEndpoint(%q): %v", tt.in, err)
			continue
		}
		if host != tt.host || secure != tt.secure {
			t.Errorf("parseEndpoint(%q) = (%q, %v), want (%q, %v)", tt.in, host, secure, tt.host, tt.secure)
		}
	}
}
