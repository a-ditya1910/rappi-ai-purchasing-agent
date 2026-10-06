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
import com.rappi.buyer.domain.SupplierEvent;
import com.rappi.buyer.planner.ReorderPlan;
import com.rappi.buyer.planner.ReorderPlanner;
import com.rappi.buyer.repo.ApprovalRepo;
import com.rappi.buyer.repo.BudgetRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.ProductRepo;
import com.rappi.buyer.repo.PurchaseOrderRepo;
import com.rappi.buyer.repo.SupplierEventRepo;
import com.rappi.buyer.supplier.SupplierMockService;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
    private final SupplierEventRepo events;
    private final ReorderPlanner planner;
    private final ConstraintEngine constraints;
    private final SupplierMockService supplierApi;
    private final ObjectMapper json;
    private final EntityManager em;
    private final Clock clock;

    public PurchaseOrderService(PurchaseOrderRepo purchaseOrders, ProductRepo products,
                                BudgetRepo budgets, InventoryRepo inventory, ApprovalRepo approvals,
                                SupplierEventRepo events, ReorderPlanner planner, ConstraintEngine constraints,
                                SupplierMockService supplierApi, ObjectMapper json,
                                EntityManager em, Clock clock) {
        this.purchaseOrders = purchaseOrders;
        this.products = products;
        this.budgets = budgets;
        this.inventory = inventory;
        this.approvals = approvals;
        this.events = events;
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
        if (newQty < 1) {
            // an order of 0 that still says CONFIRMED is a cancel nobody can see
            return new WriteResult(false, false, poId, null,
                    "cannot amend to %d units, use cancel-po to cancel it".formatted(newQty));
        }

        PoLine line = po.getLines().get(0);

        // the rules run on an amend too. a live repair once amended an order to 0,
        // below the supplier minimum, and nothing stopped it. post state, because
        // this order is already in the position and the budget
        ReorderPlan plan = planner.plan(line.getSku(), po.getNodeId(), po.getSupplierId());
        ValidationReport report = constraints.validate(new Proposal(line.getSku(), po.getNodeId(),
                po.getSupplierId(), newQty, line.getUnitPrice(), po.getExpectedDelivery(), null), plan, true);
        if (!report.blocking().isEmpty()) {
            return new WriteResult(false, false, poId, report,
                    "blocked: " + String.join("; ", report.blocking()));
        }

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

    private static final BigDecimal PRICE_SUSPECT = new BigDecimal("0.25");

    /**
     * A supplier's message about an order, already read by the agent. Nothing the
     * model extracted is trusted: the order must exist and be open, the sender must
     * be that order's supplier, the quantity must make sense and the price must be
     * believable. Only then does it change the order - and in_transit and the
     * budget move with it, in the same transaction.
     *
     * The same message sent twice is recognised by its hash and changes nothing.
     */
    @Transactional
    public Map<String, Object> applySupplierEvent(String poId, String sender, String kind,
                                                  Integer confirmedQty, LocalDate confirmedDelivery,
                                                  BigDecimal unitPrice, String rawText, String runId) {
        Map<String, Object> out = new LinkedHashMap<>();
        PurchaseOrder po = purchaseOrders.findById(poId).orElse(null);
        if (po == null) {
            return refused(out, "NO_PO", "unknown purchase order " + poId);
        }

        String hash = sha256(rawText == null ? kind + confirmedQty + confirmedDelivery + unitPrice : rawText);
        if (events.findByPoIdAndTypeAndPayloadHash(poId, kind, hash).isPresent()) {
            out.put("applied", false);
            out.put("duplicate", true);
            out.put("poId", poId);
            return out;
        }

        PoLine line = po.getLines().get(0);
        String problem = null;
        if (!po.getSupplierId().equals(sender)) {
            // the basic version of checking who sent an email. a message about
            // somebody else's order is not one we act on
            problem = "SENDER_MISMATCH: %s is %s's order, not %s'".formatted(poId, po.getSupplierId(), sender);
        } else if (!po.getStatus().isOpen()) {
            problem = "PO_CLOSED: %s is %s".formatted(poId, po.getStatus());
        } else if (confirmedDelivery != null && confirmedDelivery.isBefore(LocalDate.now(clock))) {
            // "arrive on 14 March" with no year was once read as last year. a past
            // date makes the delivery vanish from every forward simulation
            problem = "DATE_IN_PAST: delivery %s is before today %s".formatted(
                    confirmedDelivery, LocalDate.now(clock));
        } else if (confirmedQty != null && (confirmedQty < 0 || confirmedQty > line.getQtyOrdered())) {
            problem = "QTY_OUT_OF_RANGE: %d confirmed against %d ordered".formatted(confirmedQty, line.getQtyOrdered());
        } else if (unitPrice != null && unitPrice.subtract(line.getUnitPrice()).abs()
                .divide(line.getUnitPrice(), 4, RoundingMode.HALF_UP).compareTo(PRICE_SUSPECT) > 0) {
            problem = "PRICE_SUSPECT: %s against the ordered %s, over 25%% apart".formatted(unitPrice, line.getUnitPrice());
        }

        SupplierEvent e = new SupplierEvent();
        e.setPoId(poId);
        e.setType(kind);
        e.setPayload(toJson(Map.of("kind", kind,
                "confirmedQty", String.valueOf(confirmedQty),
                "confirmedDelivery", String.valueOf(confirmedDelivery),
                "unitPrice", String.valueOf(unitPrice))));
        e.setPayloadHash(hash);
        e.setReceivedAt(Instant.now(clock));
        e.setSender(sender);
        e.setAgentRunId(runId);
        e.setRawText(rawText);
        e.setApplied(problem == null);
        e.setNote(problem);
        events.save(e);

        if (problem != null) {
            return refused(out, problem.substring(0, problem.indexOf(':')), problem);
        }

        Map<String, Object> before = snapshot(po, line);
        int openBefore = open(line);
        BigDecimal valueBefore = po.getTotalValue();

        if (confirmedQty != null) {
            line.setQtyConfirmed(confirmedQty);
            po.setStatus(confirmedQty == 0 ? PoStatus.CANCELLED
                    : confirmedQty < line.getQtyOrdered() ? PoStatus.PARTIALLY_CONFIRMED
                    : PoStatus.CONFIRMED);
        }
        if (unitPrice != null) {
            line.setUnitPrice(unitPrice);
        }
        if (confirmedDelivery != null) {
            po.setExpectedDelivery(confirmedDelivery);
        }
        int openAfter = po.getStatus() == PoStatus.CANCELLED ? 0 : open(line);
        po.setTotalValue(line.getUnitPrice().multiply(BigDecimal.valueOf(openAfter)));

        Product product = products.findById(line.getSku()).orElseThrow();
        commitBudget(po.getNodeId(), product.getCategory(), po.getTotalValue().subtract(valueBefore));
        moveInTransit(po.getNodeId(), line.getSku(), openAfter - openBefore);
        purchaseOrders.saveAndFlush(po);

        out.put("applied", true);
        out.put("duplicate", false);
        out.put("eventId", e.getId());
        out.put("poId", poId);
        out.put("sku", line.getSku());
        out.put("nodeId", po.getNodeId());
        out.put("supplierId", po.getSupplierId());
        out.put("before", before);
        out.put("after", snapshot(po, line));
        out.put("shortfall", line.getQtyOrdered() - openAfter);
        return out;
    }

    private static Map<String, Object> refused(Map<String, Object> out, String code, String detail) {
        out.put("applied", false);
        out.put("error", code);
        out.put("detail", detail);
        return out;
    }

    private static Map<String, Object> snapshot(PurchaseOrder po, PoLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", po.getStatus());
        m.put("qtyOrdered", line.getQtyOrdered());
        m.put("qtyConfirmed", line.getQtyConfirmed());
        m.put("unitPrice", line.getUnitPrice());
        m.put("expectedDelivery", po.getExpectedDelivery());
        m.put("totalValue", po.getTotalValue());
        return m;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
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
