package com.rappi.buyer.planner;

import com.rappi.buyer.config.PlanningProperties;
import com.rappi.buyer.domain.DemandForecast;
import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.Node;
import com.rappi.buyer.domain.Product;
import com.rappi.buyer.repo.ForecastRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.NodeRepo;
import com.rappi.buyer.repo.ProductRepo;
import com.rappi.buyer.repo.PurchaseOrderRepo;
import com.rappi.buyer.repo.TransferOrderRepo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Can another dark store send this one what it is short of?
 *
 * Two sides, both pure arithmetic:
 *
 *   receiver  how much it needs to last until a transfer lands plus its next
 *             review - the same target-minus-position the reorder planner uses
 *   sender    how much it can give without going short itself. POL-TRANSFER-01:
 *             the sending store keeps at least its own safety stock, otherwise
 *             the shortage has only moved one store over
 *
 * The quantity is the smaller of the two, capped by the receiver's free storage,
 * and rounded DOWN to whole cases - never send more than the sender can spare.
 */
@Service
public class TransferPlanner {

    public record Option(String fromNode, int available, int keep, int spare, int qty,
                         boolean usable, String note, List<String> steps) {}

    public record TransferPlan(String sku, String toNode, int leadDays, int horizonDays,
                               int position, int target, int need, int maxStorable, int casePack,
                               List<String> steps, List<Option> options) {}

    private record Demand(double total, double mean, double std) {}

    private static final double STD_FLOOR_RATIO = 0.15;

    private final ProductRepo products;
    private final NodeRepo nodes;
    private final InventoryRepo inventory;
    private final ForecastRepo forecasts;
    private final PurchaseOrderRepo purchaseOrders;
    private final TransferOrderRepo transfers;
    private final PlanningProperties props;
    private final Clock clock;
    private final int leadDays;

    public TransferPlanner(ProductRepo products, NodeRepo nodes, InventoryRepo inventory,
                           ForecastRepo forecasts, PurchaseOrderRepo purchaseOrders,
                           TransferOrderRepo transfers, PlanningProperties props, Clock clock,
                           @Value("${app.planning.transfer-lead-days:1}") int leadDays) {
        this.products = products;
        this.nodes = nodes;
        this.inventory = inventory;
        this.forecasts = forecasts;
        this.purchaseOrders = purchaseOrders;
        this.transfers = transfers;
        this.props = props;
        this.clock = clock;
        this.leadDays = leadDays;
    }

    @Transactional(readOnly = true)
    public TransferPlan plan(String sku, String toNode) {
        LocalDate today = LocalDate.now(clock);
        Product product = products.findById(sku)
                .orElseThrow(() -> new IllegalArgumentException("unknown sku " + sku));
        Node to = nodes.findById(toNode)
                .orElseThrow(() -> new IllegalArgumentException("unknown node " + toNode));
        Inventory inv = inventory.findByNodeIdAndSku(toNode, sku)
                .orElseThrow(() -> new IllegalArgumentException("no inventory row for " + sku + " at " + toNode));
        int pack = Math.max(1, product.getCasePack());
        double z = props.z(product.getAbcClass());
        List<String> steps = new ArrayList<>();

        int horizon = leadDays + to.getReviewPeriodDays();
        steps.add("Horizon = %d day transfer + %d day review = %d days"
                .formatted(leadDays, to.getReviewPeriodDays(), horizon));

        int incoming = purchaseOrders.sumIncomingBy(toNode, sku, today.plusDays(horizon))
                + transfers.sumIncomingBy(toNode, sku, today.plusDays(horizon));
        int position = Math.max(0, inv.getOnHand()) - inv.getReserved() + incoming;
        steps.add("%s position = %d on hand - %d reserved + %d arriving = %d"
                .formatted(toNode, Math.max(0, inv.getOnHand()), inv.getReserved(), incoming, position));

        Demand d = demand(toNode, sku, today, horizon);
        if (d == null) {
            throw new IllegalStateException("no forecast for " + sku + " at " + toNode);
        }
        int ss = ReorderPlanner.safetyStock(z, horizon, d.std(), d.mean(), 0);
        int target = (int) Math.ceil(d.total()) + ss;
        int need = Math.max(0, target - position);
        steps.add("Target = %d demand + %d safety stock = %d, so %s needs %d"
                .formatted((int) Math.ceil(d.total()), ss, target, toNode, need));

        int maxStorable = (int) (to.freeStorageCm3() / Math.max(1, product.getUnitVolumeCm3())) / pack * pack;

        List<Option> options = new ArrayList<>();
        for (Inventory s : inventory.findBySku(sku)) {
            if (!s.getNodeId().equals(toNode)) {
                options.add(sender(s, sku, need, maxStorable, pack, z, today));
            }
        }
        options.sort(Comparator.comparing(Option::usable).reversed()
                .thenComparing(Comparator.comparingInt(Option::qty).reversed()));

        return new TransferPlan(sku, toNode, leadDays, horizon, position, target, need, maxStorable,
                pack, List.copyOf(steps), options);
    }

    private Option sender(Inventory s, String sku, int need, int maxStorable, int pack, double z,
                          LocalDate today) {
        Node from = nodes.findById(s.getNodeId()).orElseThrow();
        int available = s.available();
        int horizon = from.getReviewPeriodDays();

        Demand d = demand(from.getId(), sku, today, horizon);
        if (d == null) {
            return new Option(from.getId(), available, 0, 0, 0, false,
                    "no forecast at " + from.getId() + ", so what it must keep for itself is unknown",
                    List.of());
        }

        int ss = ReorderPlanner.safetyStock(z, horizon, d.std(), d.mean(), 0);
        int keep = (int) Math.ceil(d.total()) + ss;
        int spare = Math.max(0, available - keep);
        int qty = Math.min(need, Math.min(spare, maxStorable)) / pack * pack;

        List<String> steps = List.of(
                "%s has %d, must keep %d for itself (%d demand over its %d day review + %d safety stock)"
                        .formatted(from.getId(), available, keep, (int) Math.ceil(d.total()), horizon, ss),
                "Spare = %d. Send min(need %d, spare %d, storage %d) in whole cases of %d = %d"
                        .formatted(spare, need, spare, maxStorable, pack, qty));

        String note = need == 0 ? "the receiving store does not need any"
                : spare == 0 ? "nothing spare above its own safety stock"
                : null;
        return new Option(from.getId(), available, keep, spare, qty, qty > 0, note, steps);
    }

    private Demand demand(String node, String sku, LocalDate today, int days) {
        List<DemandForecast> rows = forecasts.findByNodeIdAndSkuAndForecastDateBetweenOrderByForecastDate(
                node, sku, today, today.plusDays(days - 1L));
        if (rows.isEmpty()) {
            return null;
        }
        double total = rows.stream().map(DemandForecast::getForecastUnits)
                .reduce(BigDecimal.ZERO, BigDecimal::add).doubleValue();
        double mean = total / rows.size();
        // same floor as the reorder planner: a std of 0 is a data problem, not certainty
        double std = Math.max(rows.get(0).getForecastStd().doubleValue(), mean * STD_FLOOR_RATIO);
        return new Demand(total, mean, std);
    }
}
