CREATE TABLE llm_daily_budget (
  budget_day DATE PRIMARY KEY,
  reserved_tokens BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE llm_turn_budget (
  trace_id VARCHAR(64) PRIMARY KEY,
  reserved_tokens BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
