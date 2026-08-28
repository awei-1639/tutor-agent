CREATE TABLE structured_output_events (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    task VARCHAR(64) NOT NULL,
    schema_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt BETWEEN 1 AND 2),
    raw_output_encrypted BYTEA,
    raw_output_masked TEXT NOT NULL,
    raw_output_sha256 VARCHAR(64) NOT NULL,
    validation_status VARCHAR(32) NOT NULL,
    validation_errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_structured_output_events_trace
    ON structured_output_events(trace_id, created_at DESC);
