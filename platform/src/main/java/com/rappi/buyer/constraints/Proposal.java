package com.rappi.buyer.constraints;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An action the agent wants to take, before it is allowed to take it.
 *
 * recommendedQty is what the upstream system suggested. It is nullable because
 * not every proposal starts from a recommendation, and it exists only so the
 * engine can flag how far the agent has moved from it.
 */
public record Proposal(
        String sku,
        String nodeId,
        String supplierId,
        int qty,
        BigDecimal unitPrice,
        LocalDate expectedDelivery,
        Integer recommendedQty) {

    public BigDecimal totalValue() {
        return unitPrice.multiply(BigDecimal.valueOf(qty));
    }
}
