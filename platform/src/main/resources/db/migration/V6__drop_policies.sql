-- the buying policies moved to agent/knowledge/policies, where they are chunked,
-- embedded and searched from pgvector. the keyword search that used this table is gone.
DROP TABLE policies;
