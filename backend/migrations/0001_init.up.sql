-- Initial minimal schema for Dhava. Real schema comes later.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE users (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email        text UNIQUE,
    display_name text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE activities (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users (id),
    started_at timestamptz,
    sport      text,
    status     text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE raw_recordings (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    activity_id uuid NOT NULL REFERENCES activities (id),
    storage_key text,
    format      text,
    size_bytes  bigint,
    created_at  timestamptz NOT NULL DEFAULT now()
);
