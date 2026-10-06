package com.rappi.buyer.planner;

import com.rappi.buyer.config.PlanningProperties;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.domain.SupplierProduct;
import com.rappi.buyer.repo.SupplierProductRepo;
import com.rappi.buyer.repo.SupplierRepo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which supplier should the rest of an order go to? A score, not a vibe.
 *
 * Every active supplier for the sku gets its own full plan - lead times differ,
 * so the protection period, the need and the quantity differ too - and then a
 * weighted penalty. Lower is better. The parts are returned alongside the total
 * so the model explains a real trade-off instead of inventing one:
 *
 *   price        how much dearer than the cheapest usable offer
 *   leadTime     only counts if it lands after the shelf would run out
 *   reliability  1 - the supplier's score
 *   moq          only counts if the minimum forces buying past the need
 *
 * The weights live in application.yml (app.planning.supplier-weights). They are a
 * starting point to tune against real fill rates, not a truth.
 */
@Service
public class SupplierRanker {

    public record Option(String supplierId, String name, boolean usable, String note,
                         double score, Map<String, Double> parts, Integer maxDailyCapacity,
                         ReorderPlan plan) {}

    private final SupplierProductRepo supplierProducts;
    private final SupplierRepo suppliers;
    private final ReorderPlanner planner;
    private final PlanningProperties props;

    public SupplierRanker(SupplierProductRepo supplierProducts, SupplierRepo suppliers,
                          ReorderPlanner planner, PlanningProperties props) {
        this.supplierProducts = supplierProducts;
        this.suppliers = suppliers;
        this.planner = planner;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public List<Option> rank(String sku, String nodeId) {
        List<Option> usable = new ArrayList<>();
        List<Option> unusable = new ArrayList<>();
        List<ReorderPlan> plans = new ArrayList<>();
        List<SupplierProduct> offers = new ArrayList<>();

        for (SupplierProduct sp : supplierProducts.findBySku(sku)) {
            Supplier s = suppliers.findById(sp.getSupplierId()).orElseThrow();
            if (!s.isActive()) {
                unusable.add(new Option(s.getId(), s.getName(), false, "inactive, cannot order",
                        0, Map.of(), sp.getMaxDailyCapacity(), null));
                continue;
            }
            try {
                plans.add(planner.plan(sku, nodeId, s.getId()));
                offers.add(sp);
            } catch (RuntimeException e) {
                unusable.add(new Option(s.getId(), s.getName(), false, "cannot plan: " + e.getMessage(),
                        0, Map.of(), sp.getMaxDailyCapacity(), null));
            }
        }

        BigDecimal best = plans.stream().map(ReorderPlan::unitPrice)
                .min(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        PlanningProperties.Weights w = props.supplierWeights();

        for (int i = 0; i < plans.size(); i++) {
            ReorderPlan p = plans.get(i);
            SupplierProduct sp = offers.get(i);
            Supplier s = suppliers.findById(p.supplierId()).orElseThrow();

            double cover = p.daysOfCoverNow().doubleValue();
            double need = p.rawNeed();
            int moq = Math.max(s.getMoqUnits(), sp.getMinOrderUnits());

            Map<String, Double> parts = new LinkedHashMap<>();
            parts.put("price", round(p.unitPrice().subtract(best).doubleValue() / best.doubleValue()));
            parts.put("leadTime", round(cover <= 0 ? 1 : Math.max(0, p.leadTimeDays() - cover) / cover));
            parts.put("reliability", round(1 - s.getReliabilityScore().doubleValue()));
            parts.put("moq", round(need <= 0 ? 0 : Math.max(0, moq - need) / need));

            double score = w.price() * parts.get("price") + w.leadTime() * parts.get("leadTime")
                    + w.reliability() * parts.get("reliability") + w.moq() * parts.get("moq");

            // not in the score - the weights are fixed to sum to 1 - but a quantity
            // the supplier cannot ship in a day is worth saying out loud
            String note = p.recommendedQty() > sp.getMaxDailyCapacity()
                    ? "plan of %d is over the %d a day it can ship, expect a partial fill"
                            .formatted(p.recommendedQty(), sp.getMaxDailyCapacity())
                    : null;

            usable.add(new Option(s.getId(), s.getName(), true, note, round(score), parts,
                    sp.getMaxDailyCapacity(), p));
        }

        usable.sort(Comparator.comparingDouble(Option::score));
        usable.addAll(unusable);
        return usable;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
