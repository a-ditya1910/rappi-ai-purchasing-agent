package com.rappi.buyer.config;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Tuning knobs for the planner and the constraint engine. These are business
 * settings, not constants - they get tuned against real fill rates, so they
 * live in application.yml.
 *
 * Everything here is @NotNull on purpose. Spring quietly ignores config it
 * can't bind, so a typo in the yaml would otherwise leave a field null and
 * blow up much later with an NPE in the middle of a purchasing decision.
 * With validation, a bad key fails the app at startup instead.
 */
@Validated
@ConfigurationProperties("app.planning")
public record PlanningProperties(

        /** ABC class -> service level, e.g. A=0.98. Drives the safety stock z. */
        @NotEmpty Map<String, Double> serviceLevel,

        @NotNull @Valid Cover maxCoverDays,
        @NotNull @Valid Overbuy perishableOverbuy,
        @NotNull @Valid PriceVariance priceVariance,

        /** PO value above which a human has to approve. */
        @NotNull @Positive BigDecimal valueThreshold,

        /** How far the agent may deviate from the given recommendation before approval. */
        @NotNull @Positive BigDecimal qtyDeltaApproval,

        @NotNull @Valid Weights supplierWeights) {

    public record Cover(@Positive int standard, @Positive int slowMover) {}

    public record Overbuy(@Positive double warn, @Positive double block) {
        @AssertTrue(message = "perishable-overbuy.block must be greater than .warn")
        public boolean isOrdered() {
            return block > warn;
        }
    }

    public record PriceVariance(@Positive double approval, @Positive double block) {
        @AssertTrue(message = "price-variance.block must be greater than .approval")
        public boolean isOrdered() {
            return block > approval;
        }
    }

    public record Weights(double price, double leadTime, double reliability, double moq) {
        @AssertTrue(message = "supplier-weights must sum to 1.0")
        public boolean isNormalised() {
            return Math.abs(price + leadTime + reliability + moq - 1.0) < 0.0001;
        }
    }

    /**
     * Service level -> z multiplier. Standard normal inverse CDF, but we only
     * ever need these three, so a lookup beats pulling in a stats library.
     */
    public double z(String abcClass) {
        Double sl = serviceLevel.get(abcClass);
        if (sl == null) {
            throw new IllegalArgumentException("no service level configured for abc class " + abcClass);
        }
        if (sl >= 0.98) return 2.05;
        if (sl >= 0.95) return 1.65;
        return 1.28;
    }

    public int maxCoverFor(String abcClass) {
        return "C".equals(abcClass) ? maxCoverDays.slowMover() : maxCoverDays.standard();
    }
}
