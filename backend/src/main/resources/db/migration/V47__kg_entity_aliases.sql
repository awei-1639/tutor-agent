CREATE TABLE IF NOT EXISTS kg_entity_aliases (
    id BIGSERIAL PRIMARY KEY,
    node_id VARCHAR(255) NOT NULL,
    alias TEXT NOT NULL,
    normalized_alias TEXT NOT NULL,
    alias_type VARCHAR(24) NOT NULL DEFAULT 'official',
    confidence NUMERIC(4,3) NOT NULL DEFAULT 1.000,
    source VARCHAR(32) NOT NULL DEFAULT 'seed',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(node_id, normalized_alias)
);
CREATE INDEX IF NOT EXISTS idx_kg_entity_aliases_normalized
    ON kg_entity_aliases(normalized_alias);
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_kg_entity_aliases_trgm
    ON kg_entity_aliases USING gin (normalized_alias gin_trgm_ops);
