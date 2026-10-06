-- Inter-node transfers. A dark store that is short can be sent stock by another
-- store that has more than it needs - faster and cheaper than a purchase order,
-- but it lowers the sending store's service level, so operations approves each one.
CREATE TABLE transfer_orders (
  id               VARCHAR(24) PRIMARY KEY,
  from_node        VARCHAR(24) NOT NULL,
  to_node          VARCHAR(24) NOT NULL,
  sku              VARCHAR(32) NOT NULL,
  qty              INT         NOT NULL,
  status           VARCHAR(16) NOT NULL,
  expected_arrival DATE        NOT NULL,
  idempotency_key  VARCHAR(64) NULL,
  approval_id      CHAR(36)    NULL,
  agent_run_id     CHAR(36)    NULL,
  created_at       DATETIME(3) NOT NULL,
  version          INT         NOT NULL DEFAULT 0,
  CONSTRAINT uk_to_idempotency UNIQUE (idempotency_key),
  CONSTRAINT fk_to_from FOREIGN KEY (from_node) REFERENCES nodes(id),
  CONSTRAINT fk_to_to   FOREIGN KEY (to_node)   REFERENCES nodes(id),
  CONSTRAINT fk_to_sku  FOREIGN KEY (sku)       REFERENCES products(sku)
);

CREATE INDEX ix_to_incoming ON transfer_orders (to_node, sku, status, expected_arrival);

INSERT INTO id_counters (name, next_val) VALUES ('TO', 0);

-- Usaquen's own rice demand. Without it there is no way to work out how much it
-- has to keep for itself, so it could never be a sender.
INSERT INTO demand_forecast (node_id, sku, forecast_date, forecast_units, forecast_std, model_version, generated_at) VALUES
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-10',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-11',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-12',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-13',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-14',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-15',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-16',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-17',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-18',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-19',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-20',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-21',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-22',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-23',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-24',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-25',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-26',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-27',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-28',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-29',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-30',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-03-31',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-04-01',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-04-02',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-04-03',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-04-04',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-04-05',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-04-06',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-04-07',12.00,3.00,'v4','2026-03-09 18:00:00.000'),
 ('NODE-BOG-02','SKU-RICE-5KG','2026-04-08',12.00,3.00,'v4','2026-03-09 18:00:00.000');
