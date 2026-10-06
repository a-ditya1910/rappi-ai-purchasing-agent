package com.rappi.buyer.planner;

import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.PlanningException;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.PlanningExceptionRepo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The nightly run: every sku at every store, no model anywhere.
 *
 * At a few dozen skus the agent could look at everything. At fifty thousand it
 * cannot - that is a fifty thousand model calls a night. So the cheap,
 * deterministic part runs over everything, and only the rows it flags are worth
 * an agent run or a buyer's time. That split is why the maths was kept apart
 * from the model in the first place.
 *
 * ponytail: one planner call per row, each reading its own forecast and POs.
 * fine for a few thousand rows. past that, load forecasts and open orders in
 * bulk, page through inventory, and run each node in parallel.
 */
@Service
public class BatchPlanner {

    private static final Logger log = LoggerFactory.getLogger(BatchPlanner.class);

    public record Flag(String kind, String detail, Integer suggestedQty) {}

    public record Summary(String batchId, int scanned, List<PlanningException> exceptions, long durationMs) {}

    private final InventoryRepo inventory;
    private final SupplierRanker ranker;
    private final CoverageSimulator coverage;
    private final DemandAnomalyDetector anomalies;
    private final PlanningExceptionRepo exceptions;
    private final Clock clock;

    public BatchPlanner(InventoryRepo inventory, SupplierRanker ranker, CoverageSimulator coverage,
                        DemandAnomalyDetector anomalies, PlanningExceptionRepo exceptions, Clock clock) {
        this.inventory = inventory;
        this.ranker = ranker;
        this.coverage = coverage;
        this.anomalies = anomalies;
        this.exceptions = exceptions;
        this.clock = clock;
    }

    public Summary run() {
        long t0 = System.currentTimeMillis();
        String batchId = UUID.randomUUID().toString();
        List<PlanningException> found = new ArrayList<>();
        List<Inventory> rows = inventory.findAll();

        for (Inventory row : rows) {
            String sku = row.getSku();
            String node = row.getNodeId();
            String supplier = null;
            List<Flag> flags;
            try {
                SupplierRanker.Option best = ranker.rank(sku, node).stream()
                        .filter(SupplierRanker.Option::usable).findFirst().orElse(null);
                ReorderPlan plan = best == null ? null : best.plan();
                supplier = best == null ? null : best.supplierId();
                CoverageSimulator.Coverage cov = plan == null ? null
                        : coverage.simulate(sku, node, plan.protectionPeriodDays());
                flags = classify(plan, anomalies.detect(sku, node, 60), cov);
            } catch (RuntimeException e) {
                // one bad row must not stop the other forty nine thousand
                flags = List.of(new Flag("CANNOT_PLAN", String.valueOf(e.getMessage()), null));
            }

            for (Flag f : flags) {
                PlanningException pe = new PlanningException();
                pe.setBatchId(batchId);
                pe.setNodeId(node);
                pe.setSku(sku);
                pe.setKind(f.kind());
                pe.setDetail(f.detail().length() > 500 ? f.detail().substring(0, 500) : f.detail());
                pe.setSuggestedQty(f.suggestedQty());
                pe.setSupplierId(supplier);
                pe.setCreatedAt(Instant.now(clock));
                found.add(exceptions.save(pe));
            }
        }

        long ms = System.currentTimeMillis() - t0;
        log.info("batch {}: {} rows, {} exceptions, {} ms", batchId, rows.size(), found.size(), ms);
        return new Summary(batchId, rows.size(), found, ms);
    }

    @Scheduled(cron = "${app.batch.cron:0 0 2 * * *}")
    public void nightly() {
        run();
    }

    /**
     * Pure - takes what the planner, the shelf simulation and the anomaly detector
     * said, and decides what is worth flagging. A healthy sku returns nothing.
     */
    public static List<Flag> classify(ReorderPlan plan, DemandAnomalyDetector.Anomaly anomaly,
                                      CoverageSimulator.Coverage cov) {
        List<Flag> out = new ArrayList<>();
        if (plan == null) {
            out.add(new Flag("CANNOT_PLAN", "no usable supplier for this sku", null));
            return out;
        }

        for (String w : plan.warnings()) {
            if (w.startsWith("DATA_CONFLICT")) {
                out.add(new Flag("DATA_CONFLICT", w, null));
            } else if (w.contains("forecast was generated") || w.contains("snapshot is")) {
                out.add(new Flag("STALE_DATA", w, null));
            }
        }

        if (cov != null && cov.stockoutDays() > 0) {
            out.add(new Flag("STOCKOUT_RISK", "%d days below zero from %s with what is already coming"
                    .formatted(cov.stockoutDays(), cov.firstStockout()), null));
        }

        if (plan.blocked()) {
            out.add(new Flag("NO_LEGAL_ORDER", "needs %d but no purchase is legal - %s".formatted(
                    plan.rawNeed(), plan.explanationSteps().get(plan.explanationSteps().size() - 1)), null));
        } else if (plan.recommendedQty() > 0) {
            out.add(new Flag("NEEDS_ORDER", "planner suggests %d from %s".formatted(
                    plan.recommendedQty(), plan.supplierId()), plan.recommendedQty()));
        }

        if (anomaly != null && anomaly.verdict() == DemandAnomalyDetector.Verdict.REAL) {
            out.add(new Flag("DEMAND_SHIFT", anomaly.explanation(), null));
        }
        return out;
    }
}
