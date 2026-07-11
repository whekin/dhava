-- Phase 1: user-entered activity metadata, supplied by the finish endpoint.

ALTER TABLE activities ADD COLUMN IF NOT EXISTS title text;
ALTER TABLE activities ADD COLUMN IF NOT EXISTS description text;
ALTER TABLE activities ADD COLUMN IF NOT EXISTS bike text;
ALTER TABLE activities ADD COLUMN IF NOT EXISTS bike_type text
    CONSTRAINT activities_bike_type_check
    CHECK (bike_type IN ('full_sus', 'hardtail', 'ebike', 'other'));
