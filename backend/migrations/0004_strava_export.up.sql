-- One-time Strava connection and idempotent processed-activity exports.
-- Device credentials are stored only as SHA-256 hashes. Strava OAuth tokens
-- stay server-side and never enter the Android app.

CREATE TABLE strava_connections (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_token_hash    bytea NOT NULL UNIQUE,
    oauth_state_hash     bytea UNIQUE,
    oauth_state_expires  timestamptz,
    athlete_id           bigint,
    athlete_name         text,
    scope                text,
    access_token         text,
    refresh_token        text,
    access_expires_at    timestamptz,
    status               text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'connected', 'revoked')),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX strava_connections_athlete_id_idx
    ON strava_connections (athlete_id);

CREATE TABLE strava_exports (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id        uuid NOT NULL REFERENCES strava_connections (id) ON DELETE CASCADE,
    external_id          text NOT NULL,
    title                text NOT NULL,
    description          text,
    sport_type           text NOT NULL DEFAULT 'MountainBikeRide',
    strava_upload_id     bigint,
    strava_activity_id   bigint,
    status               text NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'processing', 'uploaded', 'failed')),
    error                text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (connection_id, external_id)
);
