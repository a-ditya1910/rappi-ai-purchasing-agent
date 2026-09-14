package com.rappi.buyer.verify;

import com.rappi.buyer.constraints.ConstraintEngine;
import com.rappi.buyer.constraints.Proposal;
import com.rappi.buyer.constraints.ValidationReport;
import com.rappi.buyer.domain.PoLine;
import com.rappi.buyer.domain.PurchaseOrder;
import com.rappi.buyer.planner.CoverageSimulator;
import com.rappi.buyer.planner.ReorderPlan;
import com.rappi.buyer.planner.ReorderPlanner;
import com.rappi.buyer.tools.PurchaseOrderService;
import com.rappi.buyer.verify.VerificationReport.Diff;
import com.rappi.buyer.verify.VerificationReport.Outcome;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks our own work, three different ways.
 *
 * The principle: never trust the return value of your own write. An agent that
 * calls create-po, gets ok back and reports "done" has validated nothing - it
 * has read its own optimism.
 *
 *   L1  did the write land as written        re-read from mysql, diff vs intent
 *   L2  is the resulting world still legal   constraint engine on post-state
 *   L3  did it actually achieve the goal     re-simulate the shelf
 *
 * L3 is the one that usually gets skipped, and it is the only one that catches
 * a correctly executed but wrong decision.
 */
@Service
public class Verifier {

    private static final BigDecimal MATERIAL_PRICE_DELTA = new BigDecimal("0.02");

    private final PurchaseOrderService orders;
    private final ConstraintEngine constraints;
    private final ReorderPlanner planner;
    private final CoverageSimulator coverage;

    public Verifier(PurchaseOrderService orders, ConstraintEngine constraints,
                    ReorderPlanner planner, CoverageSimulator coverage) {
        this.orders = orders;
        this.constraints = constraints;
        this.planner = planner;
        this.coverage = coverage;
    }

    /**
     * REQUIRES_NEW matters as much as em.clear() does. Running inside the writing
     * transaction would read that transaction's own uncommitted rows, so every
     * check would pass and none of them would mean anything.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public VerificationReport verify(String poId, Proposal intended) {
        List<Diff> diffs = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        PurchaseOrder po = orders.reread(poId);
        PoLine line = po.getLines().stream()
                .filter(l -> l.getSku().equals(intended.sku()))
                .findFirst().orElse(null);

        if (line == null) {
            diffs.add(new Diff("L1", "line", intended.sku(), "missing", false));
            return new VerificationReport(poId, Outcome.UNRECOVERABLE, diffs,
                    List.of("the order has no line for " + intended.sku()));
        }

        l1(diffs, intended, po, line);
        l2(diffs, notes, intended, po, line);
        l3(diffs, notes, intended, po);

        boolean clean = diffs.stream().allMatch(Diff::ok);
        return new VerificationReport(poId, clean ? Outcome.VERIFIED : Outcome.MISMATCH,
                diffs, notes);
    }

    /** Did the database end up holding what we asked for? */
    private void l1(List<Diff> diffs, Proposal intended, PurchaseOrder po, PoLine line) {
        int confirmed = line.getQtyConfirmed() != null ? line.getQtyConfirmed() : line.getQtyOrdered();

        diffs.add(new Diff("L1", "qtyOrdered",
                str(intended.qty()), str(line.getQtyOrdered()),
                line.getQtyOrdered() == intended.qty()));

        // this is where the supplier capping an order shows up, and it is the
        // most common real mismatch rather than an injected one
        diffs.add(new Diff("L1", "qtyConfirmed",
                str(intended.qty()), str(confirmed), confirmed == intended.qty()));

        BigDecimal priceDelta = line.getUnitPrice().subtract(intended.unitPrice()).abs();
        boolean priceOk = intended.unitPrice().signum() == 0
                || priceDelta.divide(intended.unitPrice(), 4, java.math.RoundingMode.HALF_UP)
                        .compareTo(MATERIAL_PRICE_DELTA) <= 0;
        diffs.add(new Diff("L1", "unitPrice",
                str(intended.unitPrice()), str(line.getUnitPrice()), priceOk));

        diffs.add(new Diff("L1", "supplierId",
                intended.supplierId(), po.getSupplierId(),
                po.getSupplierId().equals(intended.supplierId())));

        if (intended.expectedDelivery() != null) {
            diffs.add(new Diff("L1", "expectedDelivery",
                    str(intended.expectedDelivery()), str(po.getExpectedDelivery()),
                    !po.getExpectedDelivery().isAfter(intended.expectedDelivery())));
        }
    }

    /** Is the world we just created still one the rules allow? */
    private void l2(List<Diff> diffs, List<String> notes, Proposal intended,
                    PurchaseOrder po, PoLine line) {
        int confirmed = line.getQtyConfirmed() != null ? line.getQtyConfirmed() : line.getQtyOrdered();

        ReorderPlan after = planner.plan(intended.sku(), intended.nodeId(), po.getSupplierId());
        Proposal asBuilt = new Proposal(intended.sku(), intended.nodeId(), po.getSupplierId(),
                Math.max(confirmed, 1), line.getUnitPrice(), po.getExpectedDelivery(),
                intended.recommendedQty());

        ValidationReport post = constraints.validate(asBuilt, after);
        boolean legal = !post.blocking().stream()
                // the order we just placed is obviously in the window now, that is
                // not a violation we created
                .filter(b -> !b.startsWith("DUPLICATE_PO"))
                .findAny().isPresent();

        diffs.add(new Diff("L2", "postStateLegal", "no blocking checks",
                post.blocking().isEmpty() ? "none" : String.join("; ", post.blocking()), legal));

        if (!post.blocking().isEmpty()) {
            notes.add("post-state validation: " + String.join("; ", post.blocking()));
        }
    }

    /** Forget the paperwork - will the shelf actually be empty? */
    private void l3(List<Diff> diffs, List<String> notes, Proposal intended, PurchaseOrder po) {
        ReorderPlan after = planner.plan(intended.sku(), intended.nodeId(), po.getSupplierId());
        CoverageSimulator.Coverage cov = coverage.simulate(
                intended.sku(), intended.nodeId(), after.protectionPeriodDays());

        diffs.add(new Diff("L3", "stockoutDays", "0", str(cov.stockoutDays()),
                cov.stockoutDays() == 0));

        if (cov.stockoutDays() > 0) {
            notes.add("still short: %d days below zero from %s"
                    .formatted(cov.stockoutDays(), cov.firstStockout()));
            notes.addAll(cov.timeline());
        }

        // over-ordering is a failure too, just a slower and more expensive one
        int target = after.targetPosition();
        int ending = cov.endingPosition();
        boolean notWildlyOver = target == 0 || ending <= target * 1.15 + after.recommendedQty();
        diffs.add(new Diff("L3", "endingPosition",
                "<= %d".formatted((int) (target * 1.15)), str(ending), notWildlyOver));

        diffs.add(new Diff("L3", "daysOfCover", "within limits",
                str(cov.daysOfCover()), true));
    }

    private static String str(Object o) {
        return o == null ? "null" : o.toString();
    }
}
