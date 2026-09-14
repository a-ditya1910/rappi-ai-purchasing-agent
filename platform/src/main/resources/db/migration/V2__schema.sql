-- Core master data

CREATE TABLE products (
  sku              VARCHAR(32) PRIMARY KEY,
  name             VARCHAR(128)   NOT NULL,
  category         VARCHAR(32)    NOT NULL,
  unit_cost        DECIMAL(12,4)  NOT NULL,
  case_pack        INT            NOT NULL,
  unit_volume_cm3  INT            NOT NULL,
  shelf_life_days  INT            NOT NULL,
  is_perishable    BOOLEAN        NOT NULL DEFAULT FALSE,
  abc_class        CHAR(1)        NOT NULL
);

CREATE TABLE nodes (
  id                   VARCHAR(24) PRIMARY KEY,
  name                 VARCHAR(64) NOT NULL,
  country              CHAR(2)     NOT NULL,
  type                 VARCHAR(16) NOT NULL,
  storage_capacity_cm3 BIGINT      NOT NULL,
  storage_used_cm3     BIGINT      NOT NULL DEFAULT 0,
  review_period_days   INT         NOT NULL DEFAULT 7
);

CREATE TABLE suppliers (
  id                 VARCHAR(24) PRIMARY KEY,
  name               VARCHAR(64)  NOT NULL,
  country            CHAR(2)      NOT NULL,
  lead_time_days     INT          NOT NULL,
  lead_time_std      DECIMAL(6,2) NOT NULL DEFAULT 0,
  moq_units          INT          NOT NULL DEFAULT 0,
  reliability_score  DECIMAL(4,3) NOT NULL DEFAULT 1.000,
  is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
  payment_terms_days INT          NOT NULL DEFAULT 30
);

CREATE TABLE supplier_products (
  supplier_id        VARCHAR(24)   NOT NULL,
  sku                VARCHAR(32)   NOT NULL,
  unit_price         DECIMAL(12,4) NOT NULL,
  currency           CHAR(3)       NOT NULL DEFAULT 'USD',
  min_order_units    INT           NOT NULL DEFAULT 0,
  max_daily_capacity INT           NOT NULL,
  PRIMARY KEY (supplier_id, sku),
  CONSTRAINT fk_sp_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT fk_sp_product  FOREIGN KEY (sku)         REFERENCES products(sku)
);

-- Operational state

CREATE TABLE inventory (
  node_id     VARCHAR(24)  NOT NULL,
  sku         VARCHAR(32)  NOT NULL,
  on_hand     INT          NOT NULL DEFAULT 0,
  reserved    INT          NOT NULL DEFAULT 0,
  in_transit  INT          NOT NULL DEFAULT 0,
  updated_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (node_id, sku),
  CONSTRAINT fk_inv_node    FOREIGN KEY (node_id) REFERENCES nodes(id),
  CONSTRAINT fk_inv_product FOREIGN KEY (sku)     REFERENCES products(sku)
);

CREATE TABLE demand_forecast (
  node_id        VARCHAR(24)   NOT NULL,
  sku            VARCHAR(32)   NOT NULL,
  forecast_date  DATE          NOT NULL,
  forecast_units DECIMAL(10,2) NOT NULL,
  forecast_std   DECIMAL(10,2) NOT NULL,
  model_version  VARCHAR(16)   NOT NULL,
  generated_at   DATETIME(3)   NOT NULL,
  PRIMARY KEY (node_id, sku, forecast_date)
);

CREATE TABLE sales_actuals (
  node_id    VARCHAR(24) NOT NULL,
  sku        VARCHAR(32) NOT NULL,
  sale_date  DATE        NOT NULL,
  units_sold INT         NOT NULL,
  PRIMARY KEY (node_id, sku, sale_date)
);

CREATE TABLE promotions (
  promo_id   VARCHAR(24) NOT NULL,
  sku        VARCHAR(32) NOT NULL,
  node_id    VARCHAR(24) NOT NULL,
  start_date DATE        NOT NULL,
  end_date   DATE        NOT NULL,
  PRIMARY KEY (promo_id, sku, node_id)
);

CREATE TABLE budgets (
  node_id   VARCHAR(24)   NOT NULL,
  category  VARCHAR(32)   NOT NULL,
  period    CHAR(7)       NOT NULL,           -- YYYY-MM
  allocated DECIMAL(14,2) NOT NULL,
  committed DECIMAL(14,2) NOT NULL DEFAULT 0,
  spent     DECIMAL(14,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (node_id, category, period)
);

-- Purchase orders

CREATE TABLE purchase_orders (
  id                VARCHAR(24) PRIMARY KEY,
  node_id           VARCHAR(24)   NOT NULL,
  supplier_id       VARCHAR(24)   NOT NULL,
  status            VARCHAR(24)   NOT NULL,
  expected_delivery DATE          NOT NULL,
  total_value       DECIMAL(14,2) NOT NULL,
  created_by        VARCHAR(32)   NOT NULL,
  created_at        DATETIME(3)   NOT NULL,
  version           INT           NOT NULL DEFAULT 0,
  idempotency_key   VARCHAR(64)   NULL,
  agent_run_id      CHAR(36)      NULL,
  CONSTRAINT uk_po_idempotency UNIQUE (idempotency_key),
  CONSTRAINT fk_po_node     FOREIGN KEY (node_id)     REFERENCES nodes(id),
  CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
);

CREATE INDEX ix_po_sku_lookup ON purchase_orders (node_id, status, expected_delivery);

CREATE TABLE po_lines (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  po_id         VARCHAR(24)   NOT NULL,
  sku           VARCHAR(32)   NOT NULL,
  qty_ordered   INT           NOT NULL,
  qty_confirmed INT           NULL,
  qty_received  INT           NULL,
  unit_price    DECIMAL(12,4) NOT NULL,
  CONSTRAINT fk_line_po FOREIGN KEY (po_id) REFERENCES purchase_orders(id) ON DELETE CASCADE
);

CREATE INDEX ix_po_lines_sku ON po_lines (sku, po_id);

CREATE TABLE supplier_events (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  po_id        VARCHAR(24) NOT NULL,
  type         VARCHAR(24) NOT NULL,
  payload      JSON        NOT NULL,
  payload_hash CHAR(64)    NOT NULL,
  received_at  DATETIME(3) NOT NULL,
  CONSTRAINT uk_event_dedupe UNIQUE (po_id, type, payload_hash)
);

-- Agent run trace

CREATE TABLE agent_runs (
  id                CHAR(36) PRIMARY KEY,
  scenario          VARCHAR(32) NOT NULL,
  sku               VARCHAR(32) NULL,
  node_id           VARCHAR(24) NULL,
  input             JSON        NOT NULL,
  status            VARCHAR(24) NOT NULL,
  decision          VARCHAR(16) NULL,
  final_qty         INT         NULL,
  explanation       TEXT        NULL,
  validation_report JSON        NULL,
  tokens_in         INT         NOT NULL DEFAULT 0,
  tokens_out        INT         NOT NULL DEFAULT 0,
  llm_calls         INT         NOT NULL DEFAULT 0,
  duration_ms       BIGINT      NULL,
  created_at        DATETIME(3) NOT NULL
);

CREATE TABLE agent_steps (
  run_id     CHAR(36)    NOT NULL,
  seq        INT         NOT NULL,
  type       VARCHAR(16) NOT NULL,
  name       VARCHAR(96) NOT NULL,
  payload    JSON        NULL,
  latency_ms INT         NULL,
  tokens     INT         NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (run_id, seq),
  CONSTRAINT fk_step_run FOREIGN KEY (run_id) REFERENCES agent_runs(id) ON DELETE CASCADE
);

CREATE TABLE approvals (
  id              CHAR(36) PRIMARY KEY,
  run_id          CHAR(36)    NOT NULL,
  proposed_action JSON        NOT NULL,
  reason          TEXT        NOT NULL,
  risk_tier       VARCHAR(4)  NOT NULL,
  status          VARCHAR(16) NOT NULL,
  decided_by      VARCHAR(32) NULL,
  decided_at      DATETIME(3) NULL,
  decision_note   TEXT        NULL,
  created_at      DATETIME(3) NOT NULL,
  CONSTRAINT fk_appr_run FOREIGN KEY (run_id) REFERENCES agent_runs(id) ON DELETE CASCADE
);

-- Retrieval corpus. Embeddings are a JSON array in TEXT; cosine runs in Java over
-- ~25 rows. FULLTEXT is the fallback when no embedding key is configured.
CREATE TABLE policies (
  id        VARCHAR(32) PRIMARY KEY,
  title     VARCHAR(128) NOT NULL,
  body      TEXT         NOT NULL,
  tags      VARCHAR(255) NOT NULL DEFAULT '',
  embedding TEXT         NULL,
  FULLTEXT KEY ft_policy (title, body)
) ENGINE=InnoDB;
