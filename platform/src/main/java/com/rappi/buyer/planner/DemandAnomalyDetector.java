package com.rappi.buyer.planner;

import com.rappi.buyer.domain.DemandForecast;
import com.rappi.buyer.domain.Promotion;
import com.rappi.buyer.domain.SalesActual;
import com.rappi.buyer.repo.ForecastRepo;
import com.rappi.buyer.repo.PromotionRepo;
import com.rappi.buyer.repo.SalesRepo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Has demand actually moved, or does it just look like it has?
 *
 * Deliberately not a z-score. A z-score is not robust: one huge value inflates
 * the mean and the standard deviation together, so the ratio shrinks and the
 * outlier hides behind the damage it did. That is not hypothetical here - the
 * soda series carries a single 900 unit b2b order specifically to trigger it.
 *
 * So: strip point outliers first with a tukey fence, then measure what is left
 * with a tracking signal, which is what a demand planner already watches.
 */
@Service
public class DemandAnomalyDetector {

    public enum Verdict { REAL, EXPLAINED, INSUFFICIENT_DATA }

    public record Anomaly(
            Verdict verdict,
            BigDecimal trackingSignal,
            BigDecimal robustZ,
            int sustainedDays,
            boolean promoOverlap,
            List<String> outlierDays,
            BigDecimal recentMean,
            BigDecimal priorMean,
            BigDecimal forecastMean,
            String explanation) {}

    private static final int MIN_HISTORY = 21;
    private static final int SUSTAINED_THRESHOLD = 7;
    private static final double TS_THRESHOLD = 4.0;
    /** Past this share of the series being "outlying", it is a level shift, not noise. */
    private static final double SHIFT_FRACTION = 0.20;
    /** A day counts as elevated once it is a quarter above what was forecast. */
    private static final BigDecimal ELEVATED = new BigDecimal("0.25");

    private final SalesRepo sales;
    private final ForecastRepo forecasts;
    private final PromotionRepo promotions;
    private final Clock clock;

    public DemandAnomalyDetector(SalesRepo sales, ForecastRepo forecasts,
                                 PromotionRepo promotions, Clock clock) {
        this.sales = sales;
        this.forecasts = forecasts;
        this.promotions = promotions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Anomaly detect(String sku, String nodeId, int lookbackDays) {
        LocalDate today = LocalDate.now(clock);
        List<SalesActual> rows = sales.findByNodeIdAndSkuAndSaleDateBetweenOrderBySaleDate(
                nodeId, sku, today.minusDays(lookbackDays), today.minusDays(1));

        if (rows.size() < MIN_HISTORY) {
            return insufficient("only %d days of history, need %d"
                    .formatted(rows.size(), MIN_HISTORY));
        }

        // what the forecast thought would happen, for the same days
        Map<LocalDate, BigDecimal> expected = new HashMap<>();
        forecasts.findByNodeIdAndSkuAndForecastDateBetweenOrderByForecastDate(
                        nodeId, sku, today.minusDays(lookbackDays), today.plusDays(1))
                .forEach(f -> expected.put(f.getForecastDate(), f.getForecastUnits()));

        // forecasts are generated forward, so history often has none. fall back to
        // the current forward mean, which is what the plan is actually built on.
        BigDecimal forecastMean = expected.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(1, expected.size())), 4, RoundingMode.HALF_UP);

        // ---- 1. strip point outliers before measuring anything --------------
        List<Integer> values = rows.stream().map(SalesActual::getUnitsSold).sorted().toList();
        double q1 = percentile(values, 25);
        double q3 = percentile(values, 75);
        double iqr = q3 - q1;
        double lo = q1 - 1.5 * iqr;
        double hi = q3 + 1.5 * iqr;

        List<SalesActual> candidates = rows.stream()
                .filter(r -> r.getUnitsSold() > hi || r.getUnitsSold() < lo)
                .toList();

        // A tukey fence finds isolated points. Hand it a series that has genuinely
        // shifted level and it flags the whole shifted segment, which deletes the
        // signal instead of cleaning it - chips has 14 straight elevated days and
        // the first version stripped all of them, then reported nothing happened.
        //
        // So: if more than a fifth of the series is "outlying", that is not noise,
        // that is the series having moved. Keep it all and let the tracking signal
        // and the run length speak.
        boolean looksLikeAShift = candidates.size() > rows.size() * SHIFT_FRACTION;

        List<String> outlierDays = new ArrayList<>();
        List<SalesActual> clean = new ArrayList<>();
        if (looksLikeAShift) {
            clean.addAll(rows);
        } else {
            Set<LocalDate> drop = candidates.stream()
                    .map(SalesActual::getSaleDate).collect(java.util.stream.Collectors.toSet());
            for (SalesActual r : rows) {
                if (drop.contains(r.getSaleDate())) {
                    outlierDays.add("%s (%d units)".formatted(r.getSaleDate(), r.getUnitsSold()));
                } else {
                    clean.add(r);
                }
            }
        }
        if (clean.size() < MIN_HISTORY) {
            return insufficient("too much of the series was outlying to judge");
        }

        // Run length is measured on the raw series on purpose. A sustained run is
        // the thing we are looking for, so it must not be computed on data that
        // has had the run taken out of it.
        int sustained = longestElevatedRun(rows, expected, forecastMean);

        // ---- 2. tracking signal on what is left -----------------------------
        BigDecimal sumError = BigDecimal.ZERO;
        BigDecimal sumAbsError = BigDecimal.ZERO;
        for (SalesActual r : clean) {
            BigDecimal fc = expected.getOrDefault(r.getSaleDate(), forecastMean);
            BigDecimal err = BigDecimal.valueOf(r.getUnitsSold()).subtract(fc);
            sumError = sumError.add(err);
            sumAbsError = sumAbsError.add(err.abs());
        }

        BigDecimal mad = sumAbsError.divide(BigDecimal.valueOf(clean.size()), 4, RoundingMode.HALF_UP);
        BigDecimal ts = mad.signum() == 0 ? BigDecimal.ZERO
                : sumError.divide(mad, 2, RoundingMode.HALF_UP);

        if (mad.signum() == 0) {
            return insufficient("no forecast error at all, nothing to measure");
        }

        // ---- 3. robust z on the most recent day -----------------------------
        List<Integer> cleanSorted = clean.stream().map(SalesActual::getUnitsSold).sorted().toList();
        double median = percentile(cleanSorted, 50);
        double madMedian = median(cleanSorted.stream()
                .map(v -> Math.abs(v - median)).sorted().toList());
        int latest = clean.get(clean.size() - 1).getUnitsSold();
        BigDecimal robustZ = madMedian == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(0.6745 * (latest - median) / madMedian)
                        .setScale(2, RoundingMode.HALF_UP);

        // ---- 4. is it already explained? ------------------------------------
        List<Promotion> promos = promotions.findBySkuAndNodeIdAndEndDateGreaterThanEqual(
                sku, nodeId, today.minusDays(lookbackDays));
        boolean promoOverlap = !promos.isEmpty();

        BigDecimal recentMean = meanOf(rows, Math.max(0, rows.size() - 14), rows.size());
        BigDecimal priorMean = meanOf(rows, 0, Math.max(0, rows.size() - 14));

        Verdict verdict;
        String why;
        if (Math.abs(ts.doubleValue()) > TS_THRESHOLD
                && sustained >= SUSTAINED_THRESHOLD && !promoOverlap) {
            verdict = Verdict.REAL;
            why = ("Demand has genuinely moved. Tracking signal %s is past the %.0f limit and the "
                    + "shift has held for %d days with no promotion to explain it. Recent %s/day "
                    + "against a forecast of %s/day.")
                    .formatted(ts, TS_THRESHOLD, sustained, recentMean, forecastMean.setScale(1, RoundingMode.HALF_UP));
        } else {
            verdict = Verdict.EXPLAINED;
            List<String> reasons = new ArrayList<>();
            if (promoOverlap) {
                reasons.add("promotion %s ran %s to %s".formatted(promos.get(0).getPromoId(),
                        promos.get(0).getStartDate(), promos.get(0).getEndDate()));
            }
            if (!outlierDays.isEmpty()) {
                reasons.add("one-off orders excluded: " + String.join(", ", outlierDays));
            }
            if (sustained < SUSTAINED_THRESHOLD) {
                reasons.add("elevated for only %d days, under the %d day bar"
                        .formatted(sustained, SUSTAINED_THRESHOLD));
            }
            if (Math.abs(ts.doubleValue()) <= TS_THRESHOLD) {
                reasons.add("tracking signal %s is within the %.0f limit".formatted(ts, TS_THRESHOLD));
            }
            why = "The spike is accounted for, so the purchasing plan should not change: "
                    + String.join("; ", reasons) + ".";
        }

        return new Anomaly(verdict, ts, robustZ, sustained, promoOverlap, outlierDays,
                recentMean, priorMean, forecastMean.setScale(2, RoundingMode.HALF_UP), why);
    }

    // ---- helpers -----------------------------------------------------------

    /** Longest run of consecutive days meaningfully above forecast. */
    private static int longestElevatedRun(List<SalesActual> rows,
                                          Map<LocalDate, BigDecimal> expected,
                                          BigDecimal fallback) {
        int best = 0;
        int run = 0;
        for (SalesActual r : rows) {
            BigDecimal fc = expected.getOrDefault(r.getSaleDate(), fallback);
            BigDecimal err = BigDecimal.valueOf(r.getUnitsSold()).subtract(fc);
            if (err.compareTo(fc.multiply(ELEVATED)) > 0) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
        }
        return best;
    }

    private Anomaly insufficient(String why) {
        return new Anomaly(Verdict.INSUFFICIENT_DATA, BigDecimal.ZERO, BigDecimal.ZERO,
                0, false, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "Not enough to judge, so no change is recommended: " + why);
    }

    private static BigDecimal meanOf(List<SalesActual> rows, int from, int to) {
        if (to <= from) {
            return BigDecimal.ZERO;
        }
        long sum = 0;
        for (int i = from; i < to; i++) {
            sum += rows.get(i).getUnitsSold();
        }
        return BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(to - from), 1, RoundingMode.HALF_UP);
    }

    private static double percentile(List<Integer> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        double idx = (p / 100.0) * (sorted.size() - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        return lo == hi ? sorted.get(lo) : sorted.get(lo) + (idx - lo) * (sorted.get(hi) - sorted.get(lo));
    }

    private static double median(List<Double> sorted) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
