# Replenishment playbook

How buyers in the Turbo dark store network work out what to order. This is planning documentation, not policy: when it and a buying policy disagree, the policy wins.

## Inventory position
Never order against what is on the shelf alone. The position is on hand, minus units reserved for customer orders that have not been picked yet, plus units already on their way inside the period you are planning for. Reserved stock is already sold, so counting it double counts demand.

The most common mistake is adding in-transit twice. The inventory record's in-transit figure and the list of open purchase orders describe the same physical units. Use one of them, and if they disagree, stop and find out why before ordering. An order raised against a position nobody trusts is a guess with money attached.

Only count purchase orders that arrive inside the protection period. A delivery landing after the next review cannot help with the demand before it.

## Protection period
The protection period is the supplier lead time plus the review period of the store. You order today, the goods land after the lead time, and you cannot order again until the next review. Stock has to last until a delivery from that next order could arrive, not just until this delivery lands. Using lead time alone is the classic reason stores run out a few days before every delivery.

Most dark stores review weekly, so a supplier with a five day lead time means planning for twelve days.

## Safety stock and service levels
Safety stock covers two separate uncertainties. Demand moves from day to day, and the supplier's delivery date moves too. Both need to be in the buffer. Dropping the lead time term under-protects exactly the suppliers that need it most, the unreliable ones.

Service level targets come from the product's ABC class. A items, the fast movers that customers notice first, are planned at 98 percent. B items at 95 percent and C items, the slow movers, at 90 percent. Higher service levels mean more buffer and more cash tied up, which is only worth it where a stockout really costs sales.

If a forecast reports no variation at all, treat that as a data problem rather than certainty. The planner floors the forecast deviation at 15 percent of mean demand for this reason.

## Minimum order quantities and case packs
Suppliers set a minimum order quantity and ship in case packs. Round the need up to the minimum, then up to a whole number of cases, because ordering less than the need means running short. Caps work the other way: anything that limits the order, like budget or storage space, rounds down so it is never breached.

Sometimes the minimum is far larger than the need. Before accepting a forced over-buy, check how many days of cover it creates. If it pushes cover past the maximum for the product class, or past the shelf life of a perishable, ordering nothing and asking the supplier for an exception is usually cheaper than the waste.

## Perishables
Fresh products like milk have a short shelf life, so over-buying is not just tied up cash, it is product thrown away. Compare the days of cover after the order with the shelf life. Up to 1.25 times shelf life is acceptable because sell-through is not perfectly even. Between 1.25 and 1.5 times the buyer has to approve it. Above that the order should not be placed.

Cold chain products must be received the day they arrive. A delivery date that slips by even a day changes the cover calculation and should be rechecked.

## Partial fills and short shipments
Suppliers sometimes confirm less than was ordered, usually because of capacity. The confirmation often arrives as an email rather than through the ordering system, so it has to be read and recorded before anything else is decided.

Work through it in order. First record what was actually confirmed and amend the order down so the budget for the missing units is released. Then simulate the shelf for the next two weeks with the confirmed quantity. If the store still stays above zero, note the shortfall and do nothing else. If it runs out, look at alternate suppliers for the remainder, comparing price, lead time, reliability and minimum order. A slower supplier can be worse than the shortfall if its delivery lands after the stockout anyway.

Never assume a partial confirmation means the rest will follow later unless the supplier says so in writing.

## Price changes
Compare every new unit price with the last price actually paid for the same product at the same store. Small moves are normal. More than 10 percent needs the buyer's approval and more than 25 percent needs a sourcing exception from category management. A supplier confirming a higher price than the one ordered counts as a price change, even if the order was already placed.

## Budget and month end
Every store has a monthly budget per category, made of what is allocated, what is committed to open orders and what has been spent on received goods. An order must fit in what is left. When it does not, do not split it across months to get around the control. Escalate with the options costed: a smaller order that fits, a transfer from another store, or a budget exception.

Watch utilisation near month end. Taking a category above 90 percent of its budget leaves no room for an urgent order later in the month.

## Transfers between stores
When one dark store is short and another nearby has more than it needs, a transfer is usually faster and cheaper than a purchase order. The sending store must keep at least its own safety stock afterwards, otherwise the shortage just moves from one store to the other. Transfers inside the same city take about a day.

Because a transfer lowers the service level at the sending store, operations has to approve every one. Transfers are also the first thing to check when a purchase order is impossible because of a budget or minimum order problem.

## Demand spikes and promotions
A sudden jump in sales does not mean the plan is wrong. Before changing anything, check three things. Was there a promotion running during the spike? Is the jump one or two unusually large orders, such as a business customer buying in bulk? Has it lasted at least a week?

Measure the shift with the tracking signal, the running total of forecast error divided by the average size of the error. A tracking signal above 4 that has held for seven days with no promotion behind it is a real change in demand. Anything else should be recorded and left alone. Reacting to every spike creates over-stock as soon as demand returns to normal.

When demand really has moved and the forecast is also several days old, the forecast itself is the problem. Ask for it to be refreshed rather than ordering against a number that is known to be wrong.

## Investigate or escalate
These are different answers and should not be mixed up. Investigate means the information is missing, stale or contradictory, so no decision should be made until it is fixed. Escalate means the information is good but the decision is above the agent's authority, or no legal order exists and a person has to choose between costed options.

Doing nothing is a valid outcome. A buyer would rather see a clear question than a wrong order.

## Data quality
Treat a forecast older than 48 hours and an inventory snapshot older than 6 hours as stale. Stale data is a warning, not a block, so say plainly which stale figure a decision rests on. A disagreement between the in-transit figure and the open orders always blocks until it is explained. Negative on-hand stock means a count is wrong and needs a cycle count, not an order.
