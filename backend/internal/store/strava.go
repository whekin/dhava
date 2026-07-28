package store

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// StravaConnection is the server-side OAuth state for one anonymous device
// credential. OAuth tokens deliberately never leave the backend.
type StravaConnection struct {
	ID              string
	AthleteID       *int64
	AthleteName     *string
	Scope           *string
	AccessToken     *string
	RefreshToken    *string
	AccessExpiresAt *time.Time
	Status          string
}

// StravaExport is one idempotent processed GPX delivery.
type StravaExport struct {
	ConnectionID     string
	ExternalID       string
	Title            string
	Description      string
	SportType        string
	StravaUploadID   *int64
	StravaActivityID *int64
	Status           string
	Error            *string
}

// BeginStravaConnection creates or resets the short-lived OAuth state for a
// stable device credential. The caller supplies SHA-256 hashes, never secrets.
func (s *Store) BeginStravaConnection(
	ctx context.Context,
	deviceTokenHash []byte,
	oauthStateHash []byte,
	oauthStateExpires time.Time,
) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO strava_connections (
		     device_token_hash, oauth_state_hash, oauth_state_expires, status, updated_at
		 )
		 VALUES ($1, $2, $3, 'pending', now())
		 ON CONFLICT (device_token_hash) DO UPDATE
		 SET oauth_state_hash = EXCLUDED.oauth_state_hash,
		     oauth_state_expires = EXCLUDED.oauth_state_expires,
		     status = CASE
		         WHEN strava_connections.status = 'connected' THEN 'connected'
		         ELSE 'pending'
		     END,
		     updated_at = now()`,
		deviceTokenHash, oauthStateHash, oauthStateExpires,
	)
	if err != nil {
		return fmt.Errorf("begin strava connection: %w", err)
	}
	return nil
}

// StravaConnectionByOAuthState returns a non-expired pending/connected
// connection for an OAuth callback.
func (s *Store) StravaConnectionByOAuthState(
	ctx context.Context,
	oauthStateHash []byte,
	now time.Time,
) (StravaConnection, error) {
	var connection StravaConnection
	err := s.pool.QueryRow(ctx,
		`SELECT id, athlete_id, athlete_name, scope, access_token, refresh_token,
		        access_expires_at, status
		 FROM strava_connections
		 WHERE oauth_state_hash = $1 AND oauth_state_expires > $2`,
		oauthStateHash, now,
	).Scan(
		&connection.ID,
		&connection.AthleteID,
		&connection.AthleteName,
		&connection.Scope,
		&connection.AccessToken,
		&connection.RefreshToken,
		&connection.AccessExpiresAt,
		&connection.Status,
	)
	if err == pgx.ErrNoRows {
		return StravaConnection{}, ErrStravaConnectionNotFound
	}
	if err != nil {
		return StravaConnection{}, fmt.Errorf("find strava oauth state: %w", err)
	}
	return connection, nil
}

// CompleteStravaConnection atomically consumes OAuth state and stores the
// newest rotating token pair returned by Strava.
func (s *Store) CompleteStravaConnection(
	ctx context.Context,
	id string,
	athleteID int64,
	athleteName string,
	scope string,
	accessToken string,
	refreshToken string,
	accessExpiresAt time.Time,
) error {
	tag, err := s.pool.Exec(ctx,
		`UPDATE strava_connections
		 SET athlete_id = $2,
		     athlete_name = $3,
		     scope = $4,
		     access_token = $5,
		     refresh_token = $6,
		     access_expires_at = $7,
		     status = 'connected',
		     oauth_state_hash = NULL,
		     oauth_state_expires = NULL,
		     updated_at = now()
		 WHERE id = $1`,
		id, athleteID, athleteName, scope, accessToken, refreshToken, accessExpiresAt,
	)
	if err != nil {
		return fmt.Errorf("complete strava connection: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrStravaConnectionNotFound
	}
	return nil
}

// StravaConnectionByDeviceToken authenticates an app installation using the
// SHA-256 hash of its random bearer credential.
func (s *Store) StravaConnectionByDeviceToken(
	ctx context.Context,
	deviceTokenHash []byte,
) (StravaConnection, error) {
	var connection StravaConnection
	err := s.pool.QueryRow(ctx,
		`SELECT id, athlete_id, athlete_name, scope, access_token, refresh_token,
		        access_expires_at, status
		 FROM strava_connections
		 WHERE device_token_hash = $1`,
		deviceTokenHash,
	).Scan(
		&connection.ID,
		&connection.AthleteID,
		&connection.AthleteName,
		&connection.Scope,
		&connection.AccessToken,
		&connection.RefreshToken,
		&connection.AccessExpiresAt,
		&connection.Status,
	)
	if err == pgx.ErrNoRows {
		return StravaConnection{}, ErrStravaConnectionNotFound
	}
	if err != nil {
		return StravaConnection{}, fmt.Errorf("find strava device token: %w", err)
	}
	return connection, nil
}

// UpdateStravaTokens persists every refreshed token pair. Strava refresh
// tokens rotate, so retaining an older returned value would break the next
// refresh.
func (s *Store) UpdateStravaTokens(
	ctx context.Context,
	id string,
	accessToken string,
	refreshToken string,
	accessExpiresAt time.Time,
) error {
	tag, err := s.pool.Exec(ctx,
		`UPDATE strava_connections
		 SET access_token = $2,
		     refresh_token = $3,
		     access_expires_at = $4,
		     updated_at = now()
		 WHERE id = $1 AND status = 'connected'`,
		id, accessToken, refreshToken, accessExpiresAt,
	)
	if err != nil {
		return fmt.Errorf("update strava tokens: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrStravaConnectionNotFound
	}
	return nil
}

// RevokeStravaConnection forgets unusable OAuth tokens after Strava returns
// 401. The anonymous device credential remains so the same installation can
// immediately run Connect again.
func (s *Store) RevokeStravaConnection(ctx context.Context, id string) error {
	tag, err := s.pool.Exec(ctx,
		`UPDATE strava_connections
		 SET status = 'revoked',
		     access_token = NULL,
		     refresh_token = NULL,
		     access_expires_at = NULL,
		     updated_at = now()
		 WHERE id = $1`,
		id,
	)
	if err != nil {
		return fmt.Errorf("revoke strava connection: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrStravaConnectionNotFound
	}
	return nil
}

// UpsertStravaExport returns the stable export row for an external id. Metadata
// may be refreshed while the export has not completed, but Strava identifiers
// are never cleared on retry.
func (s *Store) UpsertStravaExport(
	ctx context.Context,
	connectionID string,
	externalID string,
	title string,
	description string,
	sportType string,
) (StravaExport, error) {
	var export StravaExport
	err := s.pool.QueryRow(ctx,
		`INSERT INTO strava_exports (
		     connection_id, external_id, title, description, sport_type
		 )
		 VALUES ($1, $2, $3, NULLIF($4, ''), $5)
		 ON CONFLICT (connection_id, external_id) DO UPDATE
		 SET title = CASE
		         WHEN strava_exports.status IN ('queued', 'failed') THEN EXCLUDED.title
		         ELSE strava_exports.title
		     END,
		     description = CASE
		         WHEN strava_exports.status IN ('queued', 'failed') THEN EXCLUDED.description
		         ELSE strava_exports.description
		     END,
		     sport_type = CASE
		         WHEN strava_exports.status IN ('queued', 'failed') THEN EXCLUDED.sport_type
		         ELSE strava_exports.sport_type
		     END,
		     updated_at = now()
		 RETURNING connection_id, external_id, title, COALESCE(description, ''),
		           sport_type, strava_upload_id, strava_activity_id, status, error`,
		connectionID, externalID, title, description, sportType,
	).Scan(
		&export.ConnectionID,
		&export.ExternalID,
		&export.Title,
		&export.Description,
		&export.SportType,
		&export.StravaUploadID,
		&export.StravaActivityID,
		&export.Status,
		&export.Error,
	)
	if err != nil {
		return StravaExport{}, fmt.Errorf("upsert strava export: %w", err)
	}
	return export, nil
}

func (s *Store) MarkStravaExportProcessing(
	ctx context.Context,
	connectionID string,
	externalID string,
	uploadID int64,
) error {
	return s.updateStravaExport(
		ctx,
		connectionID,
		externalID,
		`strava_upload_id = $3, status = 'processing', error = NULL`,
		uploadID,
	)
}

func (s *Store) MarkStravaExportUploaded(
	ctx context.Context,
	connectionID string,
	externalID string,
	activityID int64,
) error {
	return s.updateStravaExport(
		ctx,
		connectionID,
		externalID,
		`strava_activity_id = $3, status = 'uploaded', error = NULL`,
		activityID,
	)
}

func (s *Store) MarkStravaExportFailed(
	ctx context.Context,
	connectionID string,
	externalID string,
	message string,
) error {
	return s.updateStravaExport(
		ctx,
		connectionID,
		externalID,
		`status = 'failed', error = $3`,
		message,
	)
}

func (s *Store) updateStravaExport(
	ctx context.Context,
	connectionID string,
	externalID string,
	setClause string,
	value any,
) error {
	query := `UPDATE strava_exports SET ` + setClause + `, updated_at = now()
	          WHERE connection_id = $1 AND external_id = $2`
	tag, err := s.pool.Exec(ctx, query, connectionID, externalID, value)
	if err != nil {
		return fmt.Errorf("update strava export: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrStravaExportNotFound
	}
	return nil
}

var (
	ErrStravaConnectionNotFound = fmt.Errorf("strava connection not found")
	ErrStravaExportNotFound     = fmt.Errorf("strava export not found")
)
