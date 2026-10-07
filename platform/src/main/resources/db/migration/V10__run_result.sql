-- /decide now answers at once and the run continues in the background, so the
-- agent's full answer is stored with the run and the console reads it from here.
-- error is set when a run dies, so it ends as FAILED instead of RUNNING forever.
ALTER TABLE agent_runs
  ADD COLUMN result JSON NULL,
  ADD COLUMN error  TEXT NULL;
