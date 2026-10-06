-- What the nightly batch found. One row per thing worth a human's or the
-- agent's attention; a sku with nothing wrong writes nothing.
CREATE TABLE planning_exceptions (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_id      CHAR(36)     NOT NULL,
  node_id       VARCHAR(24)  NOT NULL,
  sku           VARCHAR(32)  NOT NULL,
  kind          VARCHAR(24)  NOT NULL,
  detail        VARCHAR(500) NOT NULL,
  suggested_qty INT          NULL,
  supplier_id   VARCHAR(24)  NULL,
  created_at    DATETIME(3)  NOT NULL
);

CREATE INDEX ix_exceptions_batch ON planning_exceptions (batch_id);
