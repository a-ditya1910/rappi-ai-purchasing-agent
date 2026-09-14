-- Seed for the demo scenarios. Simulated today is 2026-03-10 (app.sim-date).
-- Every number here is picked so one specific reasoning path is the right one.
-- See docs/02-DATA-MODEL.md for why each value is what it is.

INSERT INTO nodes (id, name, country, type, storage_capacity_cm3, storage_used_cm3, review_period_days) VALUES
 ('NODE-BOG-01', 'Bogota Chapinero', 'CO', 'DARK_STORE', 40000000, 34000000, 7),
 ('NODE-BOG-02', 'Bogota Usaquen',   'CO', 'DARK_STORE', 40000000, 21000000, 7),
 ('NODE-MEX-01', 'CDMX Roma Norte',  'MX', 'DARK_STORE', 35000000, 20000000, 7);

INSERT INTO products (sku, name, category, unit_cost, case_pack, unit_volume_cm3, shelf_life_days, is_perishable, abc_class) VALUES
 ('SKU-MILK-1L',      'Whole Milk 1L',        'dairy',     0.9500, 12, 1100, 12, TRUE,  'A'),
 ('SKU-COFFEE-500G',  'Ground Coffee 500g',   'beverages', 3.9000, 10,  900,365, FALSE, 'A'),
 ('SKU-CHIPS-150G',   'Potato Chips 150g',    'snacks',    0.6200, 24,  800,120, FALSE, 'B'),
 ('SKU-SODA-2L',      'Cola 2L',              'beverages', 1.1000,  6, 2200,180, FALSE, 'B'),
 ('SKU-RICE-5KG',     'White Rice 5kg',       'grocery',   1.8000,  4, 5200,540, FALSE, 'C');

INSERT INTO suppliers (id, name, country, lead_time_days, lead_time_std, moq_units, reliability_score, is_active, payment_terms_days) VALUES
 ('SUP-LACTEO',  'Lacteos Andinos',     'CO',  5, 1.00, 200, 0.940, TRUE,  30),
 ('SUP-ANDINA',  'Cafe Andina',         'CO',  4, 0.80, 100, 0.880, TRUE,  30),
 ('SUP-CAFEBR',  'Cafe Brasil Import',  'BR',  9, 1.50, 100, 0.910, TRUE,  45),
 ('SUP-SNACKCO', 'SnackCo Regional',    'CO',  6, 1.20, 240, 0.960, TRUE,  30),
 ('SUP-BEBIDAS', 'Bebidas Nacional',    'CO',  3, 0.50,  60, 0.970, TRUE,  15),
 ('SUP-GRANOS',  'Granos del Valle',    'CO',  6, 2.00, 400, 0.720, FALSE, 30),
 ('SUP-ARROZMX', 'Arroz Mexicano',      'MX', 14, 3.00,1000, 0.900, TRUE,  60);

INSERT INTO supplier_products (supplier_id, sku, unit_price, currency, min_order_units, max_daily_capacity) VALUES
 ('SUP-LACTEO',  'SKU-MILK-1L',     0.9500, 'USD', 200, 4000),
 ('SUP-ANDINA',  'SKU-COFFEE-500G', 3.9000, 'USD', 100,  250),
 ('SUP-CAFEBR',  'SKU-COFFEE-500G', 4.4000, 'USD', 100, 2000),
 ('SUP-SNACKCO', 'SKU-CHIPS-150G',  0.6200, 'USD', 240, 5000),
 ('SUP-BEBIDAS', 'SKU-SODA-2L',     1.1000, 'USD',  60, 3000),
 ('SUP-GRANOS',  'SKU-RICE-5KG',    1.8000, 'USD', 400, 2000),
 ('SUP-ARROZMX', 'SKU-RICE-5KG',    2.1000, 'USD',1000, 5000);

-- Inventory. NODE-BOG-01 is where the scenarios happen.
INSERT INTO inventory (node_id, sku, on_hand, reserved, in_transit, updated_at) VALUES
 ('NODE-BOG-01', 'SKU-MILK-1L',     320,  40, 400, '2026-03-10 06:00:00.000'),
 ('NODE-BOG-01', 'SKU-COFFEE-500G', 180,  20, 500, '2026-03-10 06:00:00.000'),
 ('NODE-BOG-01', 'SKU-CHIPS-150G',  260,  20, 300, '2026-03-10 06:00:00.000'),
 ('NODE-BOG-01', 'SKU-SODA-2L',     540,  40,   0, '2026-03-10 06:00:00.000'),
 ('NODE-BOG-01', 'SKU-RICE-5KG',     90,  10,   0, '2026-03-10 06:00:00.000'),
 ('NODE-BOG-02', 'SKU-RICE-5KG',   1400,   0,   0, '2026-03-10 06:00:00.000');

INSERT INTO budgets (node_id, category, period, allocated, committed, spent) VALUES
 ('NODE-BOG-01', 'dairy',     '2026-03', 2500.00, 1620.00, 400.00),   -- 480 available
 ('NODE-BOG-01', 'beverages', '2026-03', 8000.00, 1950.00, 900.00),
 ('NODE-BOG-01', 'snacks',    '2026-03', 3000.00,  186.00, 250.00),
 ('NODE-BOG-01', 'grocery',   '2026-03', 1200.00,  700.00, 200.00),   -- 300 available
 ('NODE-BOG-02', 'grocery',   '2026-03', 1500.00,    0.00,   0.00);

-- Open POs. PO-0007 is the 400 units of milk that make the 800 recommendation wrong.
INSERT INTO purchase_orders (id, node_id, supplier_id, status, expected_delivery, total_value, created_by, created_at, version) VALUES
 ('PO-0007', 'NODE-BOG-01', 'SUP-LACTEO',  'CONFIRMED', '2026-03-13',  380.00, 'buyer:ana', '2026-03-08 09:00:00.000', 1),
 ('PO-0031', 'NODE-BOG-01', 'SUP-ANDINA',  'SUBMITTED', '2026-03-14', 1950.00, 'buyer:ana', '2026-03-09 11:00:00.000', 2),
 ('PO-0044', 'NODE-BOG-01', 'SUP-SNACKCO', 'CONFIRMED', '2026-03-16',  186.00, 'buyer:ana', '2026-03-09 15:00:00.000', 1);

INSERT INTO po_lines (po_id, sku, qty_ordered, qty_confirmed, unit_price) VALUES
 ('PO-0007', 'SKU-MILK-1L',     400, 400, 0.9500),
 ('PO-0031', 'SKU-COFFEE-500G', 500, NULL, 3.9000),
 ('PO-0044', 'SKU-CHIPS-150G',  300, 300, 0.6200);

-- Promo that explains the fake soda spike. Ended 2026-03-08.
INSERT INTO promotions (promo_id, sku, node_id, start_date, end_date) VALUES
 ('PROMO-114', 'SKU-SODA-2L', 'NODE-BOG-01', '2026-03-05', '2026-03-08');

INSERT INTO policies (id, title, body, tags) VALUES
 ('POL-PERISH-02', 'Perishable over-buy limit',
  'When a supplier minimum order quantity exceeds the calculated need for a perishable item, ordering is permitted provided the resulting days of cover does not exceed 1.25 times the product shelf life. Between 1.25 and 1.5 times, buyer approval is required. Above 1.5 times the order must not be placed; request a minimum order quantity exception from the supplier instead.',
  'perishable,moq,shelf life,over-buy,spoilage'),
 ('POL-PARTIAL-01', 'Supplier partial fulfilment',
  'If a supplier confirms less than the ordered quantity, first amend the purchase order down to the confirmed quantity so the committed budget is released. Then determine whether the shortfall causes a projected stockout within 14 days. If it does, source the remainder from an alternate supplier. If it does not, no further action is required and the shortfall should be noted for the next review cycle.',
  'partial,shortfall,supplier,fulfilment,stockout'),
 ('POL-PRICE-01', 'Price variance tolerance',
  'A unit price more than 10 percent above the last price paid for the same product requires buyer approval before the purchase order is submitted. Variance above 25 percent is not permitted without a documented sourcing exception approved by category management.',
  'price,variance,tolerance,approval,cost'),
 ('POL-BUDGET-EXC-01', 'Budget exception process',
  'Purchases that exceed the remaining category budget for the period must be escalated to the category buyer with a costed comparison of alternatives. Options should include a reduced quantity within budget, an inter-node transfer if stock exists elsewhere, and a formal budget exception request. Never split a purchase order across periods to avoid the budget control.',
  'budget,exception,escalation,finance,working capital'),
 ('POL-SUPPLIER-01', 'Inactive and low reliability suppliers',
  'Purchase orders must not be raised against a supplier marked inactive. Suppliers with a reliability score below 0.7 are blocked. Between 0.7 and 0.85 an order may proceed but the buyer should be notified and an alternate supplier considered for future replenishment.',
  'supplier,reliability,inactive,blocked,sourcing'),
 ('POL-TRANSFER-01', 'Inter-node stock transfer',
  'Stock may be transferred between nodes when the receiving node faces a projected stockout and the sending node retains at least its own safety stock after the transfer. Transfers are cheaper and faster than purchase orders but reduce service level at the sending node, so they require operations approval.',
  'transfer,inter-node,dark store,allocation'),
 ('POL-FORECAST-01', 'Acting on forecast deviation',
  'When actual sales deviate persistently from forecast, verify the deviation is not explained by a promotion or a single atypical order before changing the purchasing plan. A deviation is considered persistent when it is sustained for at least 7 days and the tracking signal exceeds 4. Isolated spikes should be recorded but must not trigger replenishment changes.',
  'forecast,demand,anomaly,promotion,tracking signal');
