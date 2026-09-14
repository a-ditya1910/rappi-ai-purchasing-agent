package com.rappi.buyer.supplier;

import com.rappi.buyer.domain.PoStatus;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.domain.SupplierProduct;
import com.rappi.buyer.repo.SupplierProductRepo;
import com.rappi.buyer.repo.SupplierRepo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Random;

/**
 * Stands in for the supplier's own systems.
 *
 * This deliberately does not echo back whatever we sent it. It applies its own
 * rules - caps quantity at what it can actually ship, slips the date when it is
 * unreliable, sometimes confirms a different price - so what comes back
 * genuinely differs from what the agent expected.
 *
 * That matters: it means the verification and repair loop fires on ordinary
 * runs rather than only when a test injects a fault. SUP-ANDINA can ship 250
 * coffee a day, so ordering 500 produces scenario 2 without anybody arranging
 * it.
 */
@Service
public class SupplierMockService {

    public record Confirmation(PoStatus status, int confirmedQty, BigDecimal confirmedPrice,
                               LocalDate confirmedDelivery, String note) {}

    private final SupplierRepo suppliers;
    private final SupplierProductRepo supplierProducts;

    /** Seeded so a demo gives the same answer twice. */
    private final long seed;

    @Value("${app.faults.supplier-cap:0}")
    private int forcedCap;

    public SupplierMockService(SupplierRepo suppliers, SupplierProductRepo supplierProducts,
                               @Value("${app.supplier-mock-seed:20260310}") long seed) {
        this.suppliers = suppliers;
        this.supplierProducts = supplierProducts;
        this.seed = seed;
    }

    public Confirmation submit(String supplierId, String sku, int qty, BigDecimal unitPrice,
                               LocalDate requestedDelivery) {
        Supplier supplier = suppliers.findById(supplierId).orElseThrow();

        if (!supplier.isActive()) {
            return new Confirmation(PoStatus.CANCELLED, 0, unitPrice, requestedDelivery,
                    supplier.getName() + " is not currently accepting orders");
        }

        SupplierProduct.Key key = new SupplierProduct.Key();
        key.setSupplierId(supplierId);
        key.setSku(sku);
        SupplierProduct sp = supplierProducts.findById(key).orElse(null);

        // deterministic per order, so the same order always gets the same answer
        Random rnd = new Random(seed + supplierId.hashCode() * 31L + sku.hashCode() + qty);

        int cap = forcedCap > 0 ? forcedCap
                : (sp != null ? sp.getMaxDailyCapacity() : Integer.MAX_VALUE);
        int confirmed = Math.min(qty, cap);

        LocalDate delivery = requestedDelivery;
        String note = null;

        if (confirmed < qty) {
            note = "can supply %d of %d requested, capacity".formatted(confirmed, qty);
        }

        // unreliable suppliers slip the date
        if (supplier.getReliabilityScore().compareTo(new BigDecimal("0.90")) < 0) {
            int slip = 1 + rnd.nextInt(3);
            delivery = delivery.plusDays(slip);
            note = (note == null ? "" : note + "; ") + "delivery slipped %d days".formatted(slip);
        }

        // and occasionally the price moves between order and confirmation
        BigDecimal price = unitPrice;
        if (sp != null && rnd.nextInt(10) == 0) {
            price = sp.getUnitPrice().multiply(new BigDecimal("1.03"))
                    .setScale(4, java.math.RoundingMode.HALF_UP);
            note = (note == null ? "" : note + "; ") + "price confirmed at " + price;
        }

        PoStatus status = confirmed == 0 ? PoStatus.CANCELLED
                : confirmed < qty ? PoStatus.PARTIALLY_CONFIRMED
                : PoStatus.CONFIRMED;

        return new Confirmation(status, confirmed, price, delivery, note);
    }
}
