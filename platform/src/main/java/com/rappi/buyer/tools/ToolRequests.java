package com.rappi.buyer.tools;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request bodies for the compute tools.
 *
 * Each record is three things at once: the http contract, the validation rules,
 * and the json schema handed to the model as a function declaration. A
 * hallucinated qty of -50 is rejected here, before any logic runs, and comes
 * back as readable text the model can correct against.
 */
public final class ToolRequests {

    private ToolRequests() {}

    public record CalculateReorder(
            @NotBlank String sku,
            @NotBlank String nodeId,
            @NotBlank String supplierId) {}

    public record ValidatePurchase(
            @NotBlank String sku,
            @NotBlank String nodeId,
            @NotBlank String supplierId,
            @Min(value = 1, message = "quantity must be at least 1") int qty,
            @NotNull @DecimalMin(value = "0.01", message = "unit price must be positive")
            BigDecimal unitPrice,
            @NotNull LocalDate expectedDelivery,
            Integer recommendedQty) {}
}
