# Buying policies

The rules a buyer, or the agent, has to follow when raising or changing a purchase order. Each policy is one section so it can be retrieved and cited on its own.

## POL-PERISH-02 · Perishable over-buy limit
When a supplier minimum order quantity exceeds the calculated need for a perishable item, ordering is permitted provided the resulting days of cover does not exceed 1.25 times the product shelf life. Between 1.25 and 1.5 times, buyer approval is required. Above 1.5 times the order must not be placed; request a minimum order quantity exception from the supplier instead.

## POL-PARTIAL-01 · Supplier partial fulfilment
If a supplier confirms less than the ordered quantity, first amend the purchase order down to the confirmed quantity so the committed budget is released. Then determine whether the shortfall causes a projected stockout within 14 days. If it does, source the remainder from an alternate supplier. If it does not, no further action is required and the shortfall should be noted for the next review cycle.

## POL-PRICE-01 · Price variance tolerance
A unit price more than 10 percent above the last price paid for the same product requires buyer approval before the purchase order is submitted. Variance above 25 percent is not permitted without a documented sourcing exception approved by category management.

## POL-BUDGET-EXC-01 · Budget exception process
Purchases that exceed the remaining category budget for the period must be escalated to the category buyer with a costed comparison of alternatives. Options should include a reduced quantity within budget, an inter-node transfer if stock exists elsewhere, and a formal budget exception request. Never split a purchase order across periods to avoid the budget control.

## POL-SUPPLIER-01 · Inactive and low reliability suppliers
Purchase orders must not be raised against a supplier marked inactive. Suppliers with a reliability score below 0.7 are blocked. Between 0.7 and 0.85 an order may proceed but the buyer should be notified and an alternate supplier considered for future replenishment.

## POL-TRANSFER-01 · Inter-node stock transfer
Stock may be transferred between nodes when the receiving node faces a projected stockout and the sending node retains at least its own safety stock after the transfer. Transfers are cheaper and faster than purchase orders but reduce service level at the sending node, so they require operations approval.

## POL-FORECAST-01 · Acting on forecast deviation
When actual sales deviate persistently from forecast, verify the deviation is not explained by a promotion or a single atypical order before changing the purchasing plan. A deviation is considered persistent when it is sustained for at least 7 days and the tracking signal exceeds 4. Isolated spikes should be recorded but must not trigger replenishment changes.
