package com.rappi.buyer.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** new = 0.8 x old + 0.2 x (0.7 x fill + 0.3 x on time). Worked by hand below. */
class ReliabilityTest {

    static BigDecimal next(String old, double fill, boolean onTime) {
        return PurchaseOrderService.nextReliability(new BigDecimal(old), fill, onTime);
    }

    @Test
    @DisplayName("a perfect delivery nudges the score up, it does not jump to 1")
    void perfect() {
        assertThat(next("0.880", 1.0, true)).isEqualByComparingTo("0.904");    // 0.704 + 0.2
    }

    @Test
    @DisplayName("andina: all of it, but late - drops under the 0.85 warn line")
    void lateButComplete() {
        assertThat(next("0.880", 1.0, false)).isEqualByComparingTo("0.844");   // 0.704 + 0.14
    }

    @Test
    @DisplayName("half of it, on time")
    void shortButOnTime() {
        assertThat(next("0.960", 0.5, true)).isEqualByComparingTo("0.898");    // 0.768 + 0.13
    }

    @Test
    @DisplayName("nothing arrived and it was late - the worst case still only moves it a fifth")
    void nothingAndLate() {
        assertThat(next("0.900", 0.0, false)).isEqualByComparingTo("0.720");
    }

    @Test
    @DisplayName("receiving more than ordered does not count as better than perfect")
    void fillIsCapped() {
        assertThat(next("0.880", 1.4, true)).isEqualByComparingTo(next("0.880", 1.0, true));
    }
}
