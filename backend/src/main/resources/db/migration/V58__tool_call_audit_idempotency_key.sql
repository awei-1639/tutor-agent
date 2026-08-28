ALTER TABLE tool_calls ADD COLUMN idempotency_key VARCHAR(128);
CREATE INDEX idx_tool_calls_idempotency ON tool_calls(tool, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
