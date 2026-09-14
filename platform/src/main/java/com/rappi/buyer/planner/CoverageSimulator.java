package com.rappi.buyer.planner;

import com.rappi.buyer.domain.DemandForecast;
import com.rappi.buyer.domain.PurchaseOrder;
import com.rappi.buyer.repo.ForecastRepo;
import com.rappi.buyer.repo.InventoryRepo;
import com.rappi.buyer.repo.PurchaseOrderRepo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks the next N days one at a time, taking forecast demand off the shelf and
 * putting purchase orders on it as they land.
 *
 * This is what makes the third level of verification mean something. Checking
 * that a PO exists tells you the write worked; checking that the shelf never
 * goes empty tells you the decision worked. They are different questions and
 * only this one answers the second.
 */
@Service
public class CoverageSimulator {

    public record Coverage(int stockoutDays, LocalDate firstStockout, int endingPosition,
                           int lowestPosition, BigDecimal daysOfCover, List<String> timeline) {}

    private final InventoryRepo inventory;
    private final ForecastRepo forecasts;
    private final PurchaseOrderRepo purchaseOrders;
    private final Clock clock;

    public CoverageSimulator(InventoryRepo inventory, ForecastRepo forecasts,
                             PurchaseOrderRepo purchaseOrders, Clock clock) {
        this.inventory = inventory;
        this.forecasts = forecasts;
        this.purchaseOrders = purchaseOrders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Coverage simulate(String sku, String nodeId, int horizonDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate end = today.plusDays(horizonDays - 1L);

        int position = inventory.findByNodeIdAndSku(nodeId, sku)
                .map(i -> i.available())
                .orElse(0);

        Map<LocalDate, BigDecimal> demand = new HashMap<>();
        for (DemandForecast f : forecasts
                .findByNodeIdAndSkuAndForecastDateBetweenOrderByForecastDate(nodeId, sku, today, end)) {
            demand.merge(f.getForecastDate(), f.getForecastUnits(), BigDecimal::add);
        }

        // incoming stock, keyed by the day it actually lands
        Map<LocalDate, Integer> arrivals = new HashMap<>();
        for (PurchaseOrder po : purchaseOrders.findOpenForSku(nodeId, sku)) {
            if (po.getExpectedDelivery().isAfter(end)) {
                continue;
            }
            int units = po.getLines().stream()
                    .filter(l -> l.getSku().equals(sku))
                    .mapToInt(l -> l.getQtyConfirmed() != null ? l.getQtyConfirmed() : l.getQtyOrdered())
                    .sum();
            arrivals.merge(po.getExpectedDelivery(), units, Integer::sum);
        }

        int stockoutDays = 0;
        int lowest = position;
        LocalDate firstStockout = null;
        BigDecimal totalDemand = BigDecimal.ZERO;
        List<String> timeline = new java.util.ArrayList<>();

        for (int d = 0; d < horizonDays; d++) {
            LocalDate day = today.plusDays(d);

            // stock arriving today is available today
            int arriving = arrivals.getOrDefault(day, 0);
            position += arriving;

            BigDecimal due = demand.getOrDefault(day, BigDecimal.ZERO);
            totalDemand = totalDemand.add(due);
            position -= due.setScale(0, RoundingMode.HALF_UP).intValue();

            if (position < 0) {
                stockoutDays++;
                if (firstStockout == null) {
                    firstStockout = day;
                }
            }
            lowest = Math.min(lowest, position);

            if (arriving > 0 || position < 0) {
                timeline.add("%s: %s%s -> position %d".formatted(day,
                        arriving > 0 ? "+" + arriving + " arrives, " : "",
                        "-" + due.setScale(0, RoundingMode.HALF_UP) + " demand",
                        position));
            }
        }

        BigDecimal meanDaily = totalDemand.signum() == 0 ? BigDecimal.ZERO
                : totalDemand.divide(BigDecimal.valueOf(horizonDays), 4, RoundingMode.HALF_UP);
        BigDecimal cover = meanDaily.signum() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(position).divide(meanDaily, 1, RoundingMode.HALF_UP);

        return new Coverage(stockoutDays, firstStockout, position, lowest, cover, timeline);
    }
}
