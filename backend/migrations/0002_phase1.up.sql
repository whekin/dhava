-- Phase 1: no auth yet, activities may exist without a user.
-- Also add ended_at, set by the finish endpoint.

ALTER TABLE activities ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE activities ADD COLUMN IF NOT EXISTS ended_at timestamptz;
