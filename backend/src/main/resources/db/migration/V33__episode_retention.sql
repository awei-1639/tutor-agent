-- Episodes are derived cross-session memory, not an indefinite transcript archive.
ALTER TABLE episodes ALTER COLUMN expires_at SET DEFAULT (now() + INTERVAL '180 days');
UPDATE episodes
SET expires_at = created_at + INTERVAL '180 days'
WHERE expires_at IS NULL;
