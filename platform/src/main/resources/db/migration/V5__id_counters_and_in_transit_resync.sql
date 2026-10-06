CREATE TABLE id_counters (
  name     VARCHAR(32) PRIMARY KEY,
  next_val BIGINT      NOT NULL
);

-- start after whatever already exists, so a database with agent-made POs keeps working
INSERT INTO id_counters (name, next_val)
SELECT 'PO', COALESCE(MAX(CAST(SUBSTRING(id, 4) AS UNSIGNED)), 0)
FROM purchase_orders WHERE id LIKE 'PO-%';

-- one-off repair: create/amend/cancel never maintained in_transit before V5, so
-- any database that already ran the agent has drifted. resync from the open POs.
UPDATE inventory i
SET in_transit = (
  SELECT COALESCE(SUM(COALESCE(l.qty_confirmed, l.qty_ordered)), 0)
  FROM purchase_orders po JOIN po_lines l ON l.po_id = po.id
  WHERE po.node_id = i.node_id AND l.sku = i.sku
    AND po.status IN ('SUBMITTED','CONFIRMED','PARTIALLY_CONFIRMED'));
