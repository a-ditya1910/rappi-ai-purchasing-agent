package com.rappi.buyer.planner;

import com.rappi.buyer.config.PlanningProperties;
import com.rappi.buyer.domain.Budget;
import com.rappi.buyer.domain.DemandForecast;
import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.Node;
import com.rappi.buyer.domain.Product;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.domain.SupplierProduct;
import com.rappi.buyer.repo.BudgetRepo;
import com.rappi.buyer.repo.ForecastRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.NodeRepo;
import com.rappi.buyer.repo.ProductRepo;
import com.rappi.buyer.repo.PurchaseOrderRepo;
import com.rappi.buyer.repo.SupplierProductRepo;
import com.rappi.buyer.repo.SupplierRepo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out how much to buy. All of it is arithmetic - no model is involved, and
 * every number the agent quotes comes from here.
 *
 * The whole method runs in one read-only transaction so inventory, open POs and
 * the forecast all come from the same snapshot. Reading them separately would let
 * a PO confirmed halfway through produce a torn view, and then the in-transit
 * conflict check below would fire on an inconsistency we created ourselves.
 */
@Service
public class ReorderPlanner {

    private static final BigDecimal STD_FLOOR_RATIO = new BigDecimal("0.15");

    private final ProductRepo products;
    private final NodeRepo nodes;
    private final SupplierRepo suppliers;
    private final SupplierProductRepo supplierProducts;
    private final InventoryRepo inventory;
    private final ForecastRepo forecasts;
    private final PurchaseOrderRepo purchaseOrders;
    private final BudgetRepo budgets;
    private final PlanningProperties props;
    private final Clock clock;

    public ReorderPlanner(ProductRepo products, NodeRepo nodes, SupplierRepo suppliers,
                          SupplierProductRepo supplierProducts, InventoryRepo inventory,
                          ForecastRepo forecasts, PurchaseOrderRepo purchaseOrders,
                          BudgetRepo budgets, PlanningProperties props, Clock clock) {
        this.products = products;
        this.nodes = nodes;
        this.suppliers = suppliers;
        this.supplierProducts = supplierProducts;
        this.inventory = inventory;
        this.forecasts = forecasts;
        this.purchaseOrders = purchaseOrders;
        this.budgets = budgets;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReorderPlan plan(String sku, String nodeId, String supplierId) {
        LocalDate today = LocalDate.now(clock);
        List<String> steps = new ArrayList<>();
        List<String> warns = new ArrayList<>();

        Product product = products.findById(sku)
                .orElseThrow(() -> new IllegalArgumentException("unknown sku " + sku));
        Node node = nodes.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("unknown node " + nodeId));
        Supplier supplier = suppliers.findById(supplierId)
                .orElseThrow(() -> new IllegalArgumentException("unknown supplier " + supplierId));
        SupplierProduct.Key spKey = new SupplierProduct.Key();
        spKey.setSupplierId(supplierId);
        spKey.setSku(sku);
        SupplierProduct sp = supplierProducts.findById(spKey)
                .orElseThrow(() -> new IllegalArgumentException(supplierId + " does not supply " + sku));

        int protection = supplier.getLeadTimeDays() + node.getReviewPeriodDays();
        steps.add("Protection period = %d lead time + %d review period = %d days"
                .formatted(supplier.getLeadTimeDays(), node.getReviewPeriodDays(), protection));

        Inventory inv = inventory.findByNodeIdAndSku(nodeId, sku)
                .orElseThrow(() -> new IllegalArgumentException("no inventory row for " + sku + " at " + nodeId));

        // Only POs landing inside the protection period cover demand inside it.
        // Anything arriving later is not help we can count on.
        int derivedInTransit = purchaseOrders.sumIncomingBy(nodeId, sku, today.plusDays(protection));
        if (derivedInTransit != inv.getInTransit()) {
            warns.add(("DATA_CONFLICT: inventory.in_transit is %d but open purchase orders arriving "
                    + "within %d days sum to %d. Not ordering against a position we cannot trust.")
                    .formatted(inv.getInTransit(), protection, derivedInTransit));
        }

        int onHand = Math.max(0, inv.getOnHand());
        if (inv.getOnHand() < 0) {
            warns.add("on_hand was negative (%d), clamped to 0. Needs a cycle count."
                    .formatted(inv.getOnHand()));
        }
        int position = onHand - inv.getReserved() + derivedInTransit;
        steps.add("Position = %d on hand - %d reserved + %d arriving within %d days = %d"
                .formatted(onHand, inv.getReserved(), derivedInTransit, protection, position));

        if (staleBy(inv.getUpdatedAt()).toHours() > 6) {
            warns.add("inventory snapshot is %d hours old".formatted(staleBy(inv.getUpdatedAt()).toHours()));
        }

        List<DemandForecast> rows = forecasts
                .findByNodeIdAndSkuAndForecastDateBetweenOrderByForecastDate(
                        nodeId, sku, today, today.plusDays(protection - 1L));
        if (rows.isEmpty()) {
            throw new IllegalStateException("no forecast for " + sku + " at " + nodeId
                    + " - cannot plan, needs a manual demand assumption");
        }
        if (rows.size() < protection) {
            warns.add("forecast only covers %d of %d protection days".formatted(rows.size(), protection));
        }
        if (staleBy(rows.get(0).getGeneratedAt()).toHours() > 48) {
            warns.add("forecast was generated %d hours ago"
                    .formatted(staleBy(rows.get(0).getGeneratedAt()).toHours()));
        }

        // Sum the daily rows rather than multiplying a mean - demand is not flat and
        // weekends matter in quick commerce.
        BigDecimal demand = rows.stream()
                .map(DemandForecast::getForecastUnits)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal meanDaily = demand.divide(BigDecimal.valueOf(rows.size()), 4, RoundingMode.HALF_UP);
        steps.add("Expected demand over %d days = %s units (mean %s/day)"
                .formatted(protection, demand.setScale(1, RoundingMode.HALF_UP),
                        meanDaily.setScale(1, RoundingMode.HALF_UP)));

        BigDecimal fcStd = rows.get(0).getForecastStd();
        BigDecimal stdFloor = meanDaily.multiply(STD_FLOOR_RATIO);
        if (fcStd.compareTo(stdFloor) < 0) {
            // std of 0 means safety stock of 0, which is a 50% service level dressed
            // up as certainty. Floor it.
            fcStd = stdFloor;
            warns.add("forecast_std was below 15%% of mean, floored to %s"
                    .formatted(stdFloor.setScale(2, RoundingMode.HALF_UP)));
        }

        double z = props.z(product.getAbcClass());
        double serviceLevel = props.serviceLevel().getOrDefault(product.getAbcClass(), 0.95);

        // Two independent sources of variance: demand wobbles day to day, and the
        // supplier's lead time wobbles too. Dropping the second term is the usual
        // textbook shortcut and it under-buffers unreliable suppliers.
        double demandVar = protection * Math.pow(fcStd.doubleValue(), 2);
        double leadVar = Math.pow(meanDaily.doubleValue() * supplier.getLeadTimeStd().doubleValue(), 2);
        double sigma = Math.sqrt(demandVar + leadVar);
        int safetyStock = (int) Math.ceil(z * sigma);
        steps.add("Safety stock = %.2f z x sqrt(%d x %.1f^2 + (%.1f x %.1f)^2) = %d units"
                .formatted(z, protection, fcStd.doubleValue(), meanDaily.doubleValue(),
                        supplier.getLeadTimeStd().doubleValue(), safetyStock));

        int target = demand.setScale(0, RoundingMode.CEILING).intValue() + safetyStock;
        int rawNeed = Math.max(0, target - position);
        steps.add("Target position = %d, current %d, so raw need = %d"
                .formatted(target, position, rawNeed));

        int moq = Math.max(supplier.getMoqUnits(), sp.getMinOrderUnits());
        int casePack = Math.max(1, product.getCasePack());

        int afterMoq = rawNeed;
        if (rawNeed > 0 && rawNeed < moq) {
            afterMoq = moq;
            steps.add("Need %d is below the %d minimum order quantity".formatted(rawNeed, moq));
        }
        int afterCasePack = afterMoq == 0 ? 0 : roundUpTo(afterMoq, casePack);
        if (afterCasePack != afterMoq) {
            steps.add("Rounded up to %d to fit case pack of %d".formatted(afterCasePack, casePack));
        }

        BigDecimal price = sp.getUnitPrice();
        BigDecimal budgetLeft = budgets
                .findByNodeIdAndCategoryAndPeriod(nodeId, product.getCategory(), period(today))
                .map(Budget::available)
                .orElse(BigDecimal.ZERO);
        int maxAffordable = roundDownTo(
                budgetLeft.divide(price, 0, RoundingMode.FLOOR).intValue(), casePack);
        int maxStorable = roundDownTo(
                (int) (node.freeStorageCm3() / Math.max(1, product.getUnitVolumeCm3())), casePack);

        int qty = Math.min(afterCasePack, Math.min(maxAffordable, maxStorable));
        if (qty < afterCasePack) {
            steps.add("Capped to %d by %s".formatted(qty,
                    maxAffordable <= maxStorable ? "available budget" : "free storage"));
        }

        // Budget and MOQ can be mutually unsatisfiable - you need 200 minimum but can
        // only afford 150. There is no legal order, and ordering 150 anyway just gets
        // rejected by the supplier. Escalate instead.
        if (qty > 0 && qty < moq) {
            steps.add("No legal order: %d is affordable/storable but the minimum is %d".formatted(qty, moq));
            qty = 0;
        }

        BigDecimal coverNow = divide(BigDecimal.valueOf(position), meanDaily);
        BigDecimal coverAfter = divide(BigDecimal.valueOf(position + qty), meanDaily);

        // Sub-MOQ: is being forced over the need actually acceptable? Gate on the hard
        // limits here; the softer warn thresholds are the constraint engine's job.
        if (qty > 0 && rawNeed < moq) {
            int maxCover = props.maxCoverFor(product.getAbcClass());
            double spoilLimit = product.isPerishable()
                    ? product.getShelfLifeDays() * props.perishableOverbuy().block()
                    : Double.MAX_VALUE;
            if (coverAfter.doubleValue() > maxCover || coverAfter.doubleValue() > spoilLimit) {
                steps.add(("Ordering the %d minimum would give %s days of cover, past the %s limit. "
                        + "Better to order nothing.").formatted(moq,
                        coverAfter.setScale(1, RoundingMode.HALF_UP),
                        coverAfter.doubleValue() > maxCover ? "%d day max cover".formatted(maxCover)
                                : "%.0f day spoilage".formatted(spoilLimit)));
                qty = 0;
                coverAfter = coverNow;
            }
        }

        if (rawNeed == 0) {
            steps.add("Position already covers the target, nothing to order");
        }

        BigDecimal cost = price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

        return new ReorderPlan(sku, nodeId, supplierId,
                onHand, inv.getReserved(), derivedInTransit, position,
                supplier.getLeadTimeDays(), node.getReviewPeriodDays(), protection,
                demand.setScale(2, RoundingMode.HALF_UP), meanDaily.setScale(2, RoundingMode.HALF_UP),
                safetyStock, serviceLevel, z,
                target, rawNeed, afterMoq, afterCasePack, maxAffordable, maxStorable, qty,
                coverNow, coverAfter, price, cost,
                List.copyOf(steps), List.copyOf(warns));
    }

    private Duration staleBy(Instant when) {
        return Duration.between(when, Instant.now(clock)).isNegative()
                ? Duration.ZERO
                : Duration.between(when, Instant.now(clock));
    }

    private static String period(LocalDate d) {
        return "%04d-%02d".formatted(d.getYear(), d.getMonthValue());
    }

    /** Round up for anything we need, so we never under-order. */
    private static int roundUpTo(int value, int multiple) {
        return ((value + multiple - 1) / multiple) * multiple;
    }

    /** Round down for anything that caps us, so we never breach the cap. */
    private static int roundDownTo(int value, int multiple) {
        return Math.max(0, (value / multiple) * multiple);
    }

    private static BigDecimal divide(BigDecimal a, BigDecimal b) {
        return b.signum() == 0 ? BigDecimal.ZERO : a.divide(b, 1, RoundingMode.HALF_UP);
    }
}
