package store

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Store runs database queries for the Dhava backend.
type Store struct {
	pool *pgxpool.Pool
}

// New creates a Store on top of an existing connection pool.
func New(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool}
}

// CreateActivity inserts a new activity in status "recording" and returns its id.
func (s *Store) CreateActivity(ctx context.Context, sport string, startedAt time.Time) (string, error) {
	var id string
	err := s.pool.QueryRow(ctx,
		`INSERT INTO activities (sport, started_at, status)
		 VALUES ($1, $2, 'recording')
		 RETURNING id`,
		sport, startedAt,
	).Scan(&id)
	if err != nil {
		return "", fmt.Errorf("insert activity: %w", err)
	}
	return id, nil
}

// ActivityExists reports whether an activity with the given id exists.
func (s *Store) ActivityExists(ctx context.Context, id string) (bool, error) {
	var one int
	err := s.pool.QueryRow(ctx, `SELECT 1 FROM activities WHERE id = $1`, id).Scan(&one)
	if err == pgx.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("query activity: %w", err)
	}
	return true, nil
}

// AttachRawRecording records an uploaded raw file for the activity and moves
// the activity to status "raw_uploaded", atomically.
func (s *Store) AttachRawRecording(ctx context.Context, activityID, storageKey, format string, sizeBytes int64) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	_, err = tx.Exec(ctx,
		`INSERT INTO raw_recordings (activity_id, storage_key, format, size_bytes)
		 VALUES ($1, $2, $3, $4)`,
		activityID, storageKey, format, sizeBytes,
	)
	if err != nil {
		return fmt.Errorf("insert raw recording: %w", err)
	}

	_, err = tx.Exec(ctx,
		`UPDATE activities SET status = 'raw_uploaded' WHERE id = $1`,
		activityID,
	)
	if err != nil {
		return fmt.Errorf("update activity status: %w", err)
	}

	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit tx: %w", err)
	}
	return nil
}

// FinishActivity sets ended_at and moves the activity to status "uploaded".
// It returns false when no activity with the given id exists.
func (s *Store) FinishActivity(ctx context.Context, id string, endedAt time.Time) (bool, error) {
	tag, err := s.pool.Exec(ctx,
		`UPDATE activities SET ended_at = $2, status = 'uploaded' WHERE id = $1`,
		id, endedAt,
	)
	if err != nil {
		return false, fmt.Errorf("finish activity: %w", err)
	}
	return tag.RowsAffected() > 0, nil
}
