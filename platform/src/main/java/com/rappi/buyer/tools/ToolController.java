package com.rappi.buyer.tools;

import com.rappi.buyer.constraints.ConstraintEngine;
import com.rappi.buyer.constraints.Proposal;
import com.rappi.buyer.constraints.ValidationReport;
import com.rappi.buyer.domain.DemandForecast;
import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.Node;
import com.rappi.buyer.domain.Product;
import com.rappi.buyer.domain.PurchaseOrder;
import com.rappi.buyer.domain.SalesActual;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.domain.SupplierProduct;
import com.rappi.buyer.planner.ReorderPlan;
import com.rappi.buyer.planner.ReorderPlanner;
import com.rappi.buyer.repo.BudgetRepo;
import com.rappi.buyer.repo.ForecastRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.NodeRepo;
import com.rappi.buyer.repo.ProductRepo;
import com.rappi.buyer.repo.PurchaseOrderRepo;
import com.rappi.buyer.repo.SalesRepo;
import com.rappi.buyer.repo.SupplierProductRepo;
import com.rappi.buyer.repo.SupplierRepo;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's entire view of the world. It has no database credentials - every
 * fact it reads and every action it takes comes through here, and the platform
 * validates independently each time.
 *
 * Tools return facts, never prose. The model composes the explanation; these
 * endpoints just hand it numbers.
 */
@RestController
@RequestMapping("/tools")
public class ToolController {

    private final ProductRepo products;
    private final NodeRepo nodes;
    private final SupplierRepo suppliers;
    private final SupplierProductRepo supplierProducts;
    private final InventoryRepo inventory;
    private final ForecastRepo forecasts;
    private final SalesRepo sales;
    private final BudgetRepo budgets;
    private final PurchaseOrderRepo purchaseOrders;
    private final ReorderPlanner planner;
    private final ConstraintEngine constraints;
    private final Clock clock;

    public ToolController(ProductRepo products, NodeRepo nodes, SupplierRepo suppliers,
                          SupplierProductRepo supplierProducts, InventoryRepo inventory,
                          ForecastRepo forecasts, SalesRepo sales, BudgetRepo budgets,
                          PurchaseOrderRepo purchaseOrders, ReorderPlanner planner,
                          ConstraintEngine constraints, Clock clock) {
        this.products = products;
        this.nodes = nodes;
        this.suppliers = suppliers;
        this.supplierProducts = supplierProducts;
        this.inventory = inventory;
        this.forecasts = forecasts;
        this.sales = sales;
        this.budgets = budgets;
        this.purchaseOrders = purchaseOrders;
        this.planner = planner;
        this.constraints = constraints;
        this.clock = clock;
    }

    // ---- compute -----------------------------------------------------------

    @PostMapping("/calculate-reorder")
    public ToolResponse<ReorderPlan> calculateReorder(
            @Valid @RequestBody ToolRequests.CalculateReorder req) {
        return ToolResponse.ok(planner.plan(req.sku(), req.nodeId(), req.supplierId()));
    }

    /**
     * Validation needs a plan for cover and data-quality context, so it runs one
     * rather than making the caller pass it back in. Slightly more work per call,
     * but the agent cannot hand us a stale plan to validate against.
     */
    @PostMapping("/validate-purchase")
    public ToolResponse<ValidationReport> validatePurchase(
            @Valid @RequestBody ToolRequests.ValidatePurchase req) {
        ReorderPlan plan = planner.plan(req.sku(), req.nodeId(), req.supplierId());
        Proposal proposal = new Proposal(req.sku(), req.nodeId(), req.supplierId(), req.qty(),
                req.unitPrice(), req.expectedDelivery(), req.recommendedQty());
        return ToolResponse.ok(constraints.validate(proposal, plan));
    }

    // ---- reads -------------------------------------------------------------

    @GetMapping("/product")
    public ToolResponse<Map<String, Object>> product(@RequestParam String sku) {
        Product p = products.findById(sku).orElseThrow(() -> unknownSku(sku));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sku", p.getSku());
        out.put("name", p.getName());
        out.put("category", p.getCategory());
        out.put("casePack", p.getCasePack());
        out.put("unitVolumeCm3", p.getUnitVolumeCm3());
        out.put("shelfLifeDays", p.getShelfLifeDays());
        out.put("isPerishable", p.isPerishable());
        out.put("abcClass", p.getAbcClass());
        return ToolResponse.ok(out);
    }

    @GetMapping("/inventory-position")
    public ToolResponse<Map<String, Object>> inventoryPosition(@RequestParam String sku,
                                                               @RequestParam String nodeId) {
        Inventory i = inventory.findByNodeIdAndSku(nodeId, sku)
                .orElseThrow(() -> new NotFound("NO_INVENTORY", "no inventory for " + sku + " at " + nodeId));
        long ageHours = Duration.between(i.getUpdatedAt(), Instant.now(clock)).toHours();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sku", sku);
        out.put("nodeId", nodeId);
        out.put("onHand", i.getOnHand());
        out.put("reserved", i.getReserved());
        out.put("inTransit", i.getInTransit());
        out.put("available", i.available());
        out.put("position", i.available() + i.getInTransit());
        out.put("updatedAt", i.getUpdatedAt());
        out.put("ageHours", ageHours);
        out.put("isStale", ageHours > 6);
        return ToolResponse.ok(out);
    }

    @GetMapping("/demand-forecast")
    public ToolResponse<Map<String, Object>> demandForecast(@RequestParam String sku,
                                                            @RequestParam String nodeId,
                                                            @RequestParam(defaultValue = "14") int horizonDays) {
        LocalDate today = LocalDate.now(clock);
        List<DemandForecast> rows = forecasts
                .findByNodeIdAndSkuAndForecastDateBetweenOrderByForecastDate(
                        nodeId, sku, today, today.plusDays(horizonDays - 1L));
        if (rows.isEmpty()) {
            throw new NotFound("NO_FORECAST", "no forecast for " + sku + " at " + nodeId);
        }
        BigDecimal total = rows.stream().map(DemandForecast::getForecastUnits)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long ageHours = Duration.between(rows.get(0).getGeneratedAt(), Instant.now(clock)).toHours();

        // summarised, not 60 raw rows - the model reasons better on the shape than
        // on the dump, and the full rows are one tool call away if it wants them
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sku", sku);
        out.put("nodeId", nodeId);
        out.put("horizonDays", rows.size());
        out.put("totalUnits", total.setScale(1, RoundingMode.HALF_UP));
        out.put("meanPerDay", total.divide(BigDecimal.valueOf(rows.size()), 1, RoundingMode.HALF_UP));
        out.put("std", rows.get(0).getForecastStd());
        out.put("modelVersion", rows.get(0).getModelVersion());
        out.put("generatedAt", rows.get(0).getGeneratedAt());
        out.put("ageHours", ageHours);
        out.put("isStale", ageHours > 48);
        out.put("firstSevenDays", rows.stream().limit(7)
                .map(r -> Map.of("date", r.getForecastDate(), "units", r.getForecastUnits()))
                .toList());
        return ToolResponse.ok(out);
    }

    @GetMapping("/sales-actuals")
    public ToolResponse<List<Map<String, Object>>> salesActuals(@RequestParam String sku,
                                                                @RequestParam String nodeId,
                                                                @RequestParam(defaultValue = "60") int lookbackDays) {
        LocalDate today = LocalDate.now(clock);
        return ToolResponse.ok(sales
                .findByNodeIdAndSkuAndSaleDateBetweenOrderBySaleDate(
                        nodeId, sku, today.minusDays(lookbackDays), today.minusDays(1))
                .stream()
                .map(s -> Map.<String, Object>of("date", s.getSaleDate(), "unitsSold", s.getUnitsSold()))
                .toList());
    }

    @GetMapping("/open-pos")
    public ToolResponse<List<Map<String, Object>>> openPos(@RequestParam String sku,
                                                           @RequestParam String nodeId) {
        LocalDate today = LocalDate.now(clock);
        return ToolResponse.ok(purchaseOrders.findOpenForSku(nodeId, sku).stream()
                .map(po -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("poId", po.getId());
                    m.put("supplierId", po.getSupplierId());
                    m.put("status", po.getStatus());
                    m.put("expectedDelivery", po.getExpectedDelivery());
                    m.put("daysOut", java.time.temporal.ChronoUnit.DAYS
                            .between(today, po.getExpectedDelivery()));
                    po.getLines().stream().filter(l -> l.getSku().equals(sku)).findFirst()
                            .ifPresent(l -> {
                                m.put("qtyOrdered", l.getQtyOrdered());
                                m.put("qtyConfirmed", l.getQtyConfirmed());
                                m.put("unitPrice", l.getUnitPrice());
                            });
                    return m;
                })
                .toList());
    }

    @GetMapping("/suppliers")
    public ToolResponse<List<Map<String, Object>>> suppliersFor(@RequestParam String sku) {
        List<SupplierProduct> offers = supplierProducts.findBySku(sku);
        if (offers.isEmpty()) {
            throw new NotFound("NO_SUPPLIERS", "no supplier supplies " + sku);
        }
        return ToolResponse.ok(offers.stream().map(sp -> {
            Supplier s = suppliers.findById(sp.getSupplierId()).orElseThrow();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supplierId", s.getId());
            m.put("name", s.getName());
            m.put("unitPrice", sp.getUnitPrice());
            m.put("moqUnits", Math.max(s.getMoqUnits(), sp.getMinOrderUnits()));
            m.put("leadTimeDays", s.getLeadTimeDays());
            m.put("leadTimeStd", s.getLeadTimeStd());
            m.put("maxDailyCapacity", sp.getMaxDailyCapacity());
            m.put("reliabilityScore", s.getReliabilityScore());
            m.put("isActive", s.isActive());
            return m;
        }).toList());
    }

    @GetMapping("/budget")
    public ToolResponse<Map<String, Object>> budget(@RequestParam String nodeId,
                                                    @RequestParam String category,
                                                    @RequestParam(required = false) String period) {
        LocalDate today = LocalDate.now(clock);
        String p = period != null ? period : "%04d-%02d".formatted(today.getYear(), today.getMonthValue());
        return budgets.findByNodeIdAndCategoryAndPeriod(nodeId, category, p)
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nodeId", nodeId);
                    m.put("category", category);
                    m.put("period", p);
                    m.put("allocated", b.getAllocated());
                    m.put("committed", b.getCommitted());
                    m.put("spent", b.getSpent());
                    m.put("available", b.available());
                    return ToolResponse.ok(m);
                })
                .orElseThrow(() -> new NotFound("NO_BUDGET",
                        "no budget for " + category + " at " + nodeId + " in " + p));
    }

    @GetMapping("/storage")
    public ToolResponse<Map<String, Object>> storage(@RequestParam String nodeId,
                                                     @RequestParam(required = false) String sku) {
        Node n = nodes.findById(nodeId)
                .orElseThrow(() -> new NotFound("NO_NODE", "unknown node " + nodeId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", nodeId);
        out.put("capacityCm3", n.getStorageCapacityCm3());
        out.put("usedCm3", n.getStorageUsedCm3());
        out.put("freeCm3", n.freeStorageCm3());
        if (sku != null) {
            products.findById(sku).ifPresent(p ->
                    out.put("approxUnitsOfSku", n.freeStorageCm3() / Math.max(1, p.getUnitVolumeCm3())));
        }
        return ToolResponse.ok(out);
    }

    @GetMapping("/po/{poId}")
    public ToolResponse<Map<String, Object>> po(@PathVariable String poId) {
        PurchaseOrder po = purchaseOrders.findById(poId)
                .orElseThrow(() -> new NotFound("NO_PO", "unknown purchase order " + poId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("poId", po.getId());
        out.put("nodeId", po.getNodeId());
        out.put("supplierId", po.getSupplierId());
        out.put("status", po.getStatus());
        out.put("expectedDelivery", po.getExpectedDelivery());
        out.put("totalValue", po.getTotalValue());
        out.put("version", po.getVersion());
        out.put("createdBy", po.getCreatedBy());
        out.put("lines", po.getLines().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sku", l.getSku());
            m.put("qtyOrdered", l.getQtyOrdered());
            m.put("qtyConfirmed", l.getQtyConfirmed());
            m.put("qtyReceived", l.getQtyReceived());
            m.put("unitPrice", l.getUnitPrice());
            return m;
        }).toList());
        return ToolResponse.ok(out);
    }

    // ---- errors ------------------------------------------------------------

    private NotFound unknownSku(String sku) {
        List<String> known = products.findAll().stream().map(Product::getSku).limit(10).toList();
        return new NotFound("SKU_NOT_FOUND", "unknown sku " + sku, known);
    }

    /** Carries a machine-readable code and, where useful, what the caller could have meant. */
    public static class NotFound extends RuntimeException {
        public final String code;
        public final transient List<String> suggestions;

        public NotFound(String code, String message) {
            this(code, message, null);
        }

        public NotFound(String code, String message, List<String> suggestions) {
            super(message);
            this.code = code;
            this.suggestions = suggestions;
        }
    }
}
