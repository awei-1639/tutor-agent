ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS resource_kind VARCHAR(32);
UPDATE knowledge_documents
SET resource_kind = 'document'
WHERE resource_kind IS NULL OR btrim(resource_kind) = '';
ALTER TABLE knowledge_documents ALTER COLUMN resource_kind SET DEFAULT 'document';
ALTER TABLE knowledge_documents ALTER COLUMN resource_kind SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_resource_kind
    ON knowledge_documents(resource_kind) WHERE deleted_at IS NULL;

ALTER TABLE kg_chunks
    ADD COLUMN IF NOT EXISTS resource_kind VARCHAR(32);
UPDATE kg_chunks
SET resource_kind = CASE
    WHEN node_type IN ('resource', 'document') THEN 'resource'
    ELSE 'non_resource'
END
WHERE resource_kind IS NULL OR btrim(resource_kind) = '';
ALTER TABLE kg_chunks ALTER COLUMN resource_kind SET DEFAULT 'non_resource';
ALTER TABLE kg_chunks ALTER COLUMN resource_kind SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_kg_chunks_resource_kind ON kg_chunks(resource_kind);
