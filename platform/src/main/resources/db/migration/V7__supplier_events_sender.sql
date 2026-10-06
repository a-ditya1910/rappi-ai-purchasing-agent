-- supplier_events existed from the start but nothing wrote to it. now every
-- inbound supplier message lands here: who sent it, what it said, whether it
-- was applied, and why not if it was refused.
ALTER TABLE supplier_events
  ADD COLUMN sender       VARCHAR(24)  NULL,
  ADD COLUMN agent_run_id CHAR(36)     NULL,
  ADD COLUMN raw_text     TEXT         NULL,
  ADD COLUMN applied      BOOLEAN      NOT NULL DEFAULT FALSE,
  ADD COLUMN note         VARCHAR(255) NULL;
