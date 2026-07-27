-- V1 遗漏: 推送 payload 需要薪资与学历 (V3 6.2), 此前仅存于 Neo4j/JSON
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS salary TEXT;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS education TEXT;
