package com.rappi.buyer.constraints;

import com.rappi.buyer.config.PlanningProperties;
import com.rappi.buyer.constraints.ValidationReport.Check;
import com.rappi.buyer.constraints.ValidationReport.Status;
import com.rappi.buyer.constraints.ValidationReport.Verdict;
import com.rappi.buyer.domain.Budget;
import com.rappi.buyer.domain.Node;
import com.rappi.buyer.domain.Product;
import com.rappi.buyer.domain.PurchaseOrder;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.domain.SupplierProduct;
import com.rappi.buyer.planner.ReorderPlan;
import com.rappi.buyer.repo.BudgetRepo;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a proposed purchase is allowed, and who is allowed to approve it.
 *
 * The agent never computes its own tier. It proposes; this says yes, no, or ask a
 * human. Because it lives behind the tool API in a different process to the model,
 * there is no prompt that talks its way past it.
 *
 * Runs three times per decision: as a pre-flight check, inside the write endpoint,
 * and again on the persisted state afterwards. Same method every time, which is
 * what makes post-execution verification mean anything.
 */
@Service
public class ConstraintEngine {

    private static final BigDecimal UTIL_WARN = new BigDecimal("0.90");
    private static final BigDecimal RELIABILITY_WARN = new BigDecimal("0.85");
    private static final BigDecimal RELIABILITY_BLOCK = new BigDecimal("0.70");

    private final ProductRepo products;
    private final NodeRepo nodes;
    private final SupplierRepo suppliers;
    private final SupplierProductRepo supplierProducts;
    private final BudgetRepo budgets;
    private final PurchaseOrderRepo purchaseOrders;
    private final PlanningProperties props;
    private final Clock clock;

    public ConstraintEngine(ProductRepo products, NodeRepo nodes, SupplierRepo suppliers,
                            SupplierProductRepo supplierProducts, BudgetRepo budgets,
                            PurchaseOrderRepo purchaseOrders, PlanningProperties props, Clock clock) {
        this.products = products;
        this.nodes = nodes;
        this.suppliers = suppliers;
        this.supplierProducts = supplierProducts;
        this.budgets = budgets;
        this.purchaseOrders = purchaseOrders;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ValidationReport validate(Proposal p, ReorderPlan plan) {
        List<Check> checks = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);

        Product product = products.findById(p.sku()).orElseThrow();
        Node node = nodes.findById(p.nodeId()).orElseThrow();
        Supplier supplier = suppliers.findById(p.supplierId()).orElseThrow();

        SupplierProduct.Key key = new SupplierProduct.Key();
        key.setSupplierId(p.supplierId());
        key.setSku(p.sku());
        SupplierProduct sp = supplierProducts.findById(key).orElse(null);

        sanity(checks, p, today);
        moqAndPack(checks, p, product, supplier, sp);
        capacity(checks, p, sp);
        budget(checks, p, product);
        storage(checks, p, product, node);
        supplierState(checks, supplier);
        cover(checks, p, product, plan);
        priceVariance(checks, p);
        duplicatePo(checks, p, plan);
        thresholds(checks, p);
        dataQuality(checks, plan);

        return assemble(checks, p, supplier);
    }

    // ---- individual checks -------------------------------------------------

    private void sanity(List<Check> out, Proposal p, LocalDate today) {
        if (p.qty() <= 0) {
            out.add(block("SANITY", "quantity must be positive, got " + p.qty()));
        } else if (p.unitPrice().signum() <= 0) {
            out.add(block("SANITY", "unit price must be positive, got " + p.unitPrice()));
        } else if (p.expectedDelivery() != null && p.expectedDelivery().isBefore(today)) {
            out.add(block("SANITY", "expected delivery " + p.expectedDelivery() + " is in the past"));
        } else {
            out.add(pass("SANITY", "quantity, price and delivery date are sane"));
        }
    }

    private void moqAndPack(List<Check> out, Proposal p, Product product, Supplier supplier,
                            SupplierProduct sp) {
        int moq = Math.max(supplier.getMoqUnits(), sp == null ? 0 : sp.getMinOrderUnits());
        if (p.qty() < moq) {
            out.add(block("MOQ", "%d is below the %d minimum order quantity".formatted(p.qty(), moq)));
        } else {
            out.add(pass("MOQ", "%d meets the %d minimum".formatted(p.qty(), moq)));
        }

        int pack = Math.max(1, product.getCasePack());
        if (p.qty() % pack != 0) {
            out.add(block("CASE_PACK", "%d is not a multiple of the case pack %d".formatted(p.qty(), pack)));
        } else {
            out.add(pass("CASE_PACK", "%d = %d x %d".formatted(p.qty(), p.qty() / pack, pack)));
        }
    }

    private void capacity(List<Check> out, Proposal p, SupplierProduct sp) {
        if (sp == null) {
            out.add(warn("MAX_CAPACITY", "no supplier catalogue entry, daily capacity unknown"));
            return;
        }
        if (p.qty() > sp.getMaxDailyCapacity()) {
            // not a block - the supplier will just short us, which the verify step
            // picks up. better to know now and split the order.
            out.add(warn("MAX_CAPACITY", ("%d exceeds the supplier daily capacity of %d, "
                    + "expect a partial fill").formatted(p.qty(), sp.getMaxDailyCapacity())));
        } else {
            out.add(pass("MAX_CAPACITY", "%d within the %d daily capacity"
                    .formatted(p.qty(), sp.getMaxDailyCapacity())));
        }
    }

    private void budget(List<Check> out, Proposal p, Product product) {
        Optional<Budget> maybe = budgets.findByNodeIdAndCategoryAndPeriod(
                p.nodeId(), product.getCategory(), period(LocalDate.now(clock)));
        if (maybe.isEmpty()) {
            out.add(block("BUDGET", "no budget row for " + product.getCategory()));
            return;
        }
        Budget b = maybe.get();
        BigDecimal available = b.available();
        BigDecimal cost = p.totalValue();

        if (cost.compareTo(available) > 0) {
            out.add(block("BUDGET", "$%s needed but only $%s available in %s"
                    .formatted(money(cost), money(available), product.getCategory())));
            return;
        }
        out.add(pass("BUDGET", "$%s of $%s available".formatted(money(cost), money(available))));

        BigDecimal afterSpend = b.getCommitted().add(b.getSpent()).add(cost);
        if (b.getAllocated().signum() > 0) {
            BigDecimal util = afterSpend.divide(b.getAllocated(), 4, RoundingMode.HALF_UP);
            if (util.compareTo(UTIL_WARN) > 0) {
                out.add(warn("BUDGET_UTIL", "this would take %s to %s%% utilisation"
                        .formatted(product.getCategory(),
                                util.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP))));
            }
        }
    }

    private void storage(List<Check> out, Proposal p, Product product, Node node) {
        long needed = (long) p.qty() * product.getUnitVolumeCm3();
        long free = node.freeStorageCm3();
        if (needed > free) {
            out.add(block("STORAGE", "%d units need %,d cm3 but only %,d cm3 is free"
                    .formatted(p.qty(), needed, free)));
        } else {
            out.add(pass("STORAGE", "%d units need %,d cm3 of %,d cm3 free"
                    .formatted(p.qty(), needed, free)));
        }
    }

    private void supplierState(List<Check> out, Supplier supplier) {
        if (!supplier.isActive()) {
            out.add(block("SUPPLIER_ACTIVE", supplier.getId() + " is inactive, cannot raise a PO against it"));
        } else {
            out.add(pass("SUPPLIER_ACTIVE", supplier.getId() + " is active"));
        }

        BigDecimal score = supplier.getReliabilityScore();
        if (score.compareTo(RELIABILITY_BLOCK) < 0) {
            out.add(block("SUPPLIER_RELIABILITY", "reliability %s is below the 0.70 floor".formatted(score)));
        } else if (score.compareTo(RELIABILITY_WARN) < 0) {
            out.add(warn("SUPPLIER_RELIABILITY", "reliability %s, consider an alternate supplier".formatted(score)));
        } else {
            out.add(pass("SUPPLIER_RELIABILITY", "reliability " + score));
        }
    }

    private void cover(List<Check> out, Proposal p, Product product, ReorderPlan plan) {
        if (plan.meanDailyDemand().signum() <= 0) {
            out.add(warn("MAX_COVER", "no demand, cannot compute days of cover"));
            return;
        }
        BigDecimal coverAfter = BigDecimal.valueOf(plan.inventoryPosition() + p.qty())
                .divide(plan.meanDailyDemand(), 1, RoundingMode.HALF_UP);

        int maxCover = props.maxCoverFor(product.getAbcClass());
        if (coverAfter.doubleValue() > maxCover) {
            out.add(warn("MAX_COVER", "%s days of cover exceeds the %d day limit for class %s"
                    .formatted(coverAfter, maxCover, product.getAbcClass())));
        } else {
            out.add(pass("MAX_COVER", "%s days of cover, limit %d".formatted(coverAfter, maxCover)));
        }

        if (!product.isPerishable()) {
            return;
        }
        double warnAt = product.getShelfLifeDays() * props.perishableOverbuy().warn();
        double blockAt = product.getShelfLifeDays() * props.perishableOverbuy().block();
        double ratio = coverAfter.doubleValue() / product.getShelfLifeDays();

        if (coverAfter.doubleValue() > blockAt) {
            out.add(block("SHELF_LIFE", "%s days of cover on a %d day product (%.2fx), over the %.2fx limit"
                    .formatted(coverAfter, product.getShelfLifeDays(), ratio, props.perishableOverbuy().block())));
        } else if (coverAfter.doubleValue() > warnAt) {
            out.add(warn("SHELF_LIFE", "%s days of cover on a %d day product (%.2fx), some spoilage risk"
                    .formatted(coverAfter, product.getShelfLifeDays(), ratio)));
        } else {
            out.add(pass("SHELF_LIFE", "%s days of cover within the %d day shelf life"
                    .formatted(coverAfter, product.getShelfLifeDays())));
        }
    }

    private void priceVariance(List<Check> out, Proposal p) {
        // no price history table - the last PO line for this sku is the same
        // information without another thing to keep in sync
        List<BigDecimal> recent = purchaseOrders.lastPaidPrices(p.nodeId(), p.sku());
        if (recent.isEmpty()) {
            out.add(pass("PRICE_VARIANCE", "no purchase history for this sku, nothing to compare"));
            return;
        }
        BigDecimal last = recent.get(0);
        if (last.signum() <= 0) {
            out.add(pass("PRICE_VARIANCE", "last paid price unusable, skipping"));
            return;
        }
        BigDecimal delta = p.unitPrice().subtract(last)
                .divide(last, 4, RoundingMode.HALF_UP);
        BigDecimal pct = delta.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);

        if (delta.doubleValue() > props.priceVariance().block()) {
            out.add(block("PRICE_VARIANCE", "%s%% above the last paid %s, over the hard limit"
                    .formatted(pct, money(last))));
        } else if (delta.doubleValue() > props.priceVariance().approval()) {
            out.add(approval("PRICE_VARIANCE", "%s%% above the last paid %s, needs buyer approval"
                    .formatted(pct, money(last))));
        } else {
            out.add(pass("PRICE_VARIANCE", "%s%% versus the last paid %s".formatted(pct, money(last))));
        }
    }

    private void duplicatePo(List<Check> out, Proposal p, ReorderPlan plan) {
        LocalDate window = LocalDate.now(clock).plusDays(plan.protectionPeriodDays());
        List<PurchaseOrder> open = purchaseOrders.findOpenForSku(p.nodeId(), p.sku());
        Optional<PurchaseOrder> clash = open.stream()
                .filter(po -> !po.getExpectedDelivery().isAfter(window))
                .findFirst();

        if (clash.isPresent()) {
            PurchaseOrder po = clash.get();
            out.add(block("DUPLICATE_PO", ("%s already covers this sku, arriving %s inside the %d day "
                    + "window. Amend it rather than raising another.")
                    .formatted(po.getId(), po.getExpectedDelivery(), plan.protectionPeriodDays())));
        } else {
            out.add(pass("DUPLICATE_PO", "no open PO for this sku in the protection window"));
        }
    }

    private void thresholds(List<Check> out, Proposal p) {
        BigDecimal total = p.totalValue();
        if (total.compareTo(props.valueThreshold()) > 0) {
            out.add(approval("VALUE_THRESHOLD", "$%s is over the $%s auto-approval limit"
                    .formatted(money(total), money(props.valueThreshold()))));
        } else {
            out.add(pass("VALUE_THRESHOLD", "$%s is within the $%s limit"
                    .formatted(money(total), money(props.valueThreshold()))));
        }

        if (p.recommendedQty() == null || p.recommendedQty() <= 0) {
            return;
        }
        double delta = Math.abs(p.qty() - p.recommendedQty()) / (double) p.recommendedQty();
        if (delta > props.qtyDeltaApproval().doubleValue()) {
            out.add(approval("QTY_DELTA", "%d versus the recommended %d is %.1f%%, needs buyer sign off"
                    .formatted(p.qty(), p.recommendedQty(),
                            (p.qty() - p.recommendedQty()) * 100.0 / p.recommendedQty())));
        } else {
            out.add(pass("QTY_DELTA", "%d is within %.0f%% of the recommended %d"
                    .formatted(p.qty(), props.qtyDeltaApproval().doubleValue() * 100, p.recommendedQty())));
        }
    }

    /**
     * The planner already worked out whether the data is trustworthy. Rather than
     * recompute it, promote what it found into checks - a data conflict has to
     * block, staleness only warns.
     */
    private void dataQuality(List<Check> out, ReorderPlan plan) {
        boolean conflict = false;
        for (String w : plan.warnings()) {
            if (w.startsWith("DATA_CONFLICT")) {
                out.add(block("DATA_CONFLICT", w));
                conflict = true;
            } else if (w.contains("forecast was generated") || w.contains("snapshot is")) {
                out.add(warn("DATA_FRESHNESS", w));
            } else {
                out.add(warn("DATA_QUALITY", w));
            }
        }
        if (!conflict) {
            out.add(pass("DATA_CONFLICT", "in transit matches the open purchase orders"));
        }

        if (plan.daysOfCoverNow().doubleValue() < plan.leadTimeDays()) {
            out.add(warn("LEAD_TIME_FEASIBLE", ("only %s days of cover but the lead time is %d - "
                    + "a stockout is already unavoidable, this order limits it rather than prevents it")
                    .formatted(plan.daysOfCoverNow(), plan.leadTimeDays())));
        } else {
            out.add(pass("LEAD_TIME_FEASIBLE", "%s days of cover against a %d day lead time"
                    .formatted(plan.daysOfCoverNow(), plan.leadTimeDays())));
        }
    }

    // ---- verdict -----------------------------------------------------------

    private ValidationReport assemble(List<Check> checks, Proposal p, Supplier supplier) {
        List<String> blocking = checks.stream()
                .filter(c -> c.status() == Status.BLOCK)
                .map(c -> c.id() + ": " + c.detail())
                .toList();

        boolean hasApproval = checks.stream().anyMatch(c -> c.status() == Status.APPROVAL);
        boolean hasWarn = checks.stream().anyMatch(c -> c.status() == Status.WARN);
        boolean overValue = p.totalValue().compareTo(props.valueThreshold()) > 0;

        Verdict verdict;
        if (!blocking.isEmpty()) {
            verdict = Verdict.BLOCKED;
        } else if (hasApproval) {
            verdict = Verdict.NEEDS_APPROVAL;
        } else if (hasWarn) {
            verdict = Verdict.PASS_WITH_WARNINGS;
        } else {
            verdict = Verdict.PASS;
        }

        // Tier is decided here and nowhere else. A model asked whether it is
        // allowed to do something will eventually answer yes.
        String tier = (!blocking.isEmpty() || hasApproval || overValue || !supplier.isActive())
                ? "T3"
                : "T2";

        return new ValidationReport(verdict, tier, List.copyOf(checks), blocking,
                verdict == Verdict.NEEDS_APPROVAL || verdict == Verdict.BLOCKED);
    }

    // ---- helpers -----------------------------------------------------------

    private static Check pass(String id, String detail)     { return new Check(id, Status.PASS, detail); }
    private static Check warn(String id, String detail)     { return new Check(id, Status.WARN, detail); }
    private static Check approval(String id, String detail) { return new Check(id, Status.APPROVAL, detail); }
    private static Check block(String id, String detail)    { return new Check(id, Status.BLOCK, detail); }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String period(LocalDate d) {
        return "%04d-%02d".formatted(d.getYear(), d.getMonthValue());
    }
}
