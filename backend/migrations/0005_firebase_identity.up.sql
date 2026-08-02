-- Link Nakvali users to verified Firebase identities.

ALTER TABLE users ADD COLUMN firebase_uid text;
ALTER TABLE users ADD COLUMN avatar_url text;
ALTER TABLE users ADD COLUMN email_verified boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE users ADD CONSTRAINT users_firebase_uid_key UNIQUE (firebase_uid);
