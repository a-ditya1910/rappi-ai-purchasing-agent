package com.rappi.buyer.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rappi.buyer.constraints.ConstraintEngine;
import com.rappi.buyer.constraints.Proposal;
import com.rappi.buyer.constraints.ValidationReport;
import com.rappi.buyer.domain.Approval;
import com.rappi.buyer.domain.PoLine;
import com.rappi.buyer.domain.PoStatus;
import com.rappi.buyer.domain.Product;
import com.rappi.buyer.domain.PurchaseOrder;
import com.rappi.buyer.planner.ReorderPlan;
import com.rappi.buyer.planner.ReorderPlanner;
import com.rappi.buyer.repo.ApprovalRepo;
import com.rappi.buyer.repo.BudgetRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.ProductRepo;
import com.rappi.buyer.repo.PurchaseOrderRepo;
import com.rappi.buyer.supplier.SupplierMockService;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Everything that actually changes a purchase order.
 *
 * Three things are load bearing here and each exists because of a specific way
 * ordering systems go wrong:
 *
 *  - the tier gate, so the agent cannot act on a decision a human owns
 *  - the unique idempotency key, so a retried write cannot become two orders
 *  - em.clear() before the read back, so verification reads the database
 *    rather than the object it just built
 */
@Service
public class PurchaseOrderService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);

    public record WriteResult(boolean executed, boolean idempotent, String poId,
                              ValidationReport validation, String message) {}

    private final PurchaseOrderRepo purchaseOrders;
    private final ProductRepo products;
    private final BudgetRepo budgets;
    private final InventoryRepo inventory;
    private final ApprovalRepo approvals;
    private final ReorderPlanner planner;
    private final ConstraintEngine constraints;
    private final SupplierMockService supplierApi;
    private final ObjectMapper json;
    private final EntityManager em;
    private final Clock clock;

    public PurchaseOrderService(PurchaseOrderRepo purchaseOrders, ProductRepo products,
                                BudgetRepo budgets, InventoryRepo inventory, ApprovalRepo approvals,
                                ReorderPlanner planner, ConstraintEngine constraints,
                                SupplierMockService supplierApi, ObjectMapper json,
                                EntityManager em, Clock clock) {
        this.purchaseOrders = purchaseOrders;
        this.products = products;
        this.budgets = budgets;
        this.inventory = inventory;
        this.approvals = approvals;
        this.planner = planner;
        this.constraints = constraints;
        this.supplierApi = supplierApi;
        this.json = json;
        this.em = em;
        this.clock = clock;
    }

    @Transactional
    public WriteResult create(String sku, String nodeId, String supplierId, int qty,
                              BigDecimal unitPrice, LocalDate expectedDelivery,
                              String idempotencyKey, Integer recommendedQty,
                              String runId, String createdBy) {
        return create(sku, nodeId, supplierId, qty, unitPrice, expectedDelivery,
                idempotencyKey, recommendedQty, runId, createdBy, null);
    }

    @Transactional
    public WriteResult create(String sku, String nodeId, String supplierId, int qty,
                              BigDecimal unitPrice, LocalDate expectedDelivery,
                              String idempotencyKey, Integer recommendedQty,
                              String runId, String createdBy, String approvalId) {

        // a retry after a timeout must return the original, not make a second one
        Optional<PurchaseOrder> existing = purchaseOrders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new WriteResult(true, true, existing.get().getId(), null,
                    "already created under this idempotency key");
        }

        ReorderPlan plan = planner.plan(sku, nodeId, supplierId);
        Proposal proposal = new Proposal(sku, nodeId, supplierId, qty, unitPrice,
                expectedDelivery, recommendedQty);
        ValidationReport report = constraints.validate(proposal, plan);

        // a buyer's approval lifts the APPROVAL level checks for exactly the order they
        // approved. it never lifts a BLOCK - a human can grant authority, not make an
        // illegal order legal.
        if (!report.blocking().isEmpty()) {
            return new WriteResult(false, false, null, report,
                    "blocked: " + String.join("; ", report.blocking()));
        }
        if (report.requiresApproval() || "T3".equals(report.riskTier())) {
            String why = approvalId == null ? "tier " + report.riskTier() + ", needs a buyer to approve it"
                    : approvalProblem(approvalId, runId, sku, nodeId, supplierId, qty, unitPrice);
            if (why != null) {
                return new WriteResult(false, false, null, report, why);
            }
        }

        Product product = products.findById(sku).orElseThrow();

        // Ask the supplier before writing anything, so the row lands in one insert
        // already holding what was actually confirmed. Saving as SUBMITTED and
        // updating afterwards meant two writes to a versioned row in one
        // transaction, which is a fight with jpa for no benefit here.
        //
        // qtyOrdered still records what we asked for, so a capped order is
        // visible as ordered 500 / confirmed 250 - which is the mismatch L1 is
        // there to catch.
        SupplierMockService.Confirmation conf = supplierApi.submit(
                supplierId, sku, qty, unitPrice, expectedDelivery);

        BigDecimal price = conf.confirmedPrice();
        BigDecimal total = price.multiply(BigDecimal.valueOf(Math.max(conf.confirmedQty(), 0)));

        PurchaseOrder po = new PurchaseOrder();
        po.setId(nextPoId());
        po.setNodeId(nodeId);
        po.setSupplierId(supplierId);
        po.setStatus(conf.status());
        po.setExpectedDelivery(conf.confirmedDelivery());
        po.setTotalValue(total);
        po.setCreatedBy(createdBy);
        po.setCreatedAt(Instant.now(clock));
        po.setIdempotencyKey(idempotencyKey);
        po.setAgentRunId(runId);

        PoLine line = new PoLine();
        line.setSku(sku);
        line.setQtyOrdered(qty);
        line.setQtyConfirmed(conf.confirmedQty());
        line.setUnitPrice(price);
        po.getLines().add(line);

        try {
            purchaseOrders.saveAndFlush(po);
        } catch (DataIntegrityViolationException e) {
            // two requests raced past the check above. the unique index is the
            // real guarantee - the lookup is only an optimisation.
            log.info("idempotency key {} lost the race, returning the winner", idempotencyKey);
            return purchaseOrders.findByIdempotencyKey(idempotencyKey)
                    .map(w -> new WriteResult(true, true, w.getId(), report, "created concurrently"))
                    .orElseThrow(() -> e);
        }

        commitBudget(nodeId, product.getCategory(), total);
        if (po.getStatus() != PoStatus.CANCELLED) {
            moveInTransit(nodeId, sku, conf.confirmedQty());
        }

        return new WriteResult(true, false, po.getId(), report,
                conf.note() == null ? "confirmed in full" : conf.note());
    }

    @Transactional
    public WriteResult amend(String poId, int newQty, int expectedVersion, String reason) {
        PurchaseOrder po = purchaseOrders.findById(poId)
                .orElseThrow(() -> new ToolController.NotFound("NO_PO", "unknown purchase order " + poId));

        if (po.getVersion() != expectedVersion) {
            // somebody changed it since the agent read it. do not clobber them.
            return new WriteResult(false, false, poId, null,
                    "STALE_VERSION: expected %d but the order is at %d, re-read it first"
                            .formatted(expectedVersion, po.getVersion()));
        }
        if (po.getStatus() == PoStatus.RECEIVED || po.getStatus() == PoStatus.CANCELLED) {
            return new WriteResult(false, false, poId, null,
                    "cannot amend an order that is " + po.getStatus());
        }

        PoLine line = po.getLines().get(0);
        int openBefore = open(line);
        BigDecimal before = po.getTotalValue();
        line.setQtyOrdered(newQty);
        if (line.getQtyConfirmed() != null) {
            line.setQtyConfirmed(Math.min(line.getQtyConfirmed(), newQty));
        }
        BigDecimal after = line.getUnitPrice().multiply(BigDecimal.valueOf(newQty));
        po.setTotalValue(after);

        Product product = products.findById(line.getSku()).orElseThrow();
        commitBudget(po.getNodeId(), product.getCategory(), after.subtract(before));
        moveInTransit(po.getNodeId(), line.getSku(), open(line) - openBefore);

        try {
            purchaseOrders.saveAndFlush(po);
        } catch (ObjectOptimisticLockingFailureException e) {
            return new WriteResult(false, false, poId, null,
                    "STALE_VERSION: the order changed while we were amending it");
        }
        return new WriteResult(true, false, poId, null, reason);
    }

    @Transactional
    public WriteResult cancel(String poId, int expectedVersion, String reason) {
        PurchaseOrder po = purchaseOrders.findById(poId)
                .orElseThrow(() -> new ToolController.NotFound("NO_PO", "unknown purchase order " + poId));

        if (po.getVersion() != expectedVersion) {
            return new WriteResult(false, false, poId, null,
                    "STALE_VERSION: expected %d but the order is at %d"
                            .formatted(expectedVersion, po.getVersion()));
        }
        // cancelling twice used to release the budget twice
        if (po.getStatus() == PoStatus.CANCELLED || po.getStatus() == PoStatus.RECEIVED) {
            return new WriteResult(false, false, poId, null, "order is already " + po.getStatus());
        }

        PoLine line = po.getLines().get(0);
        Product product = products.findById(line.getSku()).orElseThrow();
        commitBudget(po.getNodeId(), product.getCategory(), po.getTotalValue().negate());
        moveInTransit(po.getNodeId(), line.getSku(), -open(line));

        po.setStatus(PoStatus.CANCELLED);
        purchaseOrders.saveAndFlush(po);
        return new WriteResult(true, false, poId, null, reason);
    }

    /**
     * Moves money in and out of committed. Cancelling passes a negative delta,
     * which is what makes the saga compensation in the verifier work.
     */
    private void commitBudget(String nodeId, String category, BigDecimal delta) {
        String period = "%04d-%02d".formatted(LocalDate.now(clock).getYear(),
                LocalDate.now(clock).getMonthValue());
        budgets.findByNodeIdAndCategoryAndPeriod(nodeId, category, period).ifPresent(b -> {
            b.setCommitted(b.getCommitted().add(delta).max(BigDecimal.ZERO));
            budgets.save(b);
        });
    }

    // in_transit has to move in the same transaction as the order, otherwise the
    // planner's conflict check sees the two disagree and blocks the next order
    private void moveInTransit(String nodeId, String sku, int delta) {
        if (delta == 0) return;
        inventory.findByNodeIdAndSku(nodeId, sku).ifPresent(i -> {
            i.setInTransit(Math.max(0, i.getInTransit() + delta));
            inventory.save(i);
        });
    }

    private static int open(PoLine l) {
        return l.getQtyConfirmed() != null ? l.getQtyConfirmed() : l.getQtyOrdered();
    }

    private String approvalProblem(String id, String runId, String sku, String nodeId,
                                   String supplierId, int qty, BigDecimal price) {
        Approval a = approvals.findById(id).orElse(null);
        if (a == null) return "unknown approval " + id;
        if (a.getStatus() != Approval.Status.APPROVED) return "approval " + id + " is " + a.getStatus();
        if (!a.getRunId().equals(runId)) return "approval " + id + " belongs to another run";

        Map<String, Object> act;
        try {
            act = json.readValue(a.getProposedAction(), new TypeReference<>() {});
        } catch (Exception e) {
            return "approval " + id + " has an unreadable action";
        }
        // the buyer approved one specific order. anything else needs its own approval
        boolean same = sku.equals(act.get("sku")) && nodeId.equals(act.get("nodeId"))
                && supplierId.equals(act.get("supplierId"))
                && act.get("qty") instanceof Number q && q.intValue() == qty
                && act.get("unitPrice") != null
                && new BigDecimal(act.get("unitPrice").toString()).compareTo(price) == 0;
        return same ? null : "approval " + id + " was for a different order";
    }

    /**
     * Re-read straight from mysql.
     *
     * em.clear() is the whole point. Without it jpa hands back the same instance
     * we just saved, so the "read back" compares an object with itself and
     * verification proves nothing at all.
     */
    @Transactional(readOnly = true)
    public PurchaseOrder reread(String poId) {
        em.clear();
        PurchaseOrder po = purchaseOrders.findById(poId).orElseThrow();
        po.getLines().size();     // touch the lines before the session closes
        return po;
    }

    private String nextPoId() {
        // LAST_INSERT_ID(expr) remembers the value for this connection only, and the
        // UPDATE row-locks the counter, so two concurrent creates can't get the same id
        em.createNativeQuery(
                "UPDATE id_counters SET next_val = LAST_INSERT_ID(next_val + 1) WHERE name = 'PO'")
                .executeUpdate();
        long n = ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        return "PO-%04d".formatted(n);
    }
}
