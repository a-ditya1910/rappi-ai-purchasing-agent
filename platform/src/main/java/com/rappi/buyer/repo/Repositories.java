package com.rappi.buyer.repo;

import com.rappi.buyer.domain.Budget;
import com.rappi.buyer.domain.DemandForecast;
import com.rappi.buyer.domain.Inventory;
import com.rappi.buyer.domain.Node;
import com.rappi.buyer.domain.Policy;
import com.rappi.buyer.domain.Product;
import com.rappi.buyer.domain.Promotion;
import com.rappi.buyer.domain.PurchaseOrder;
import com.rappi.buyer.domain.SalesActual;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.domain.SupplierProduct;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * All repositories in one file. They are interfaces with no bodies, so keeping
 * them together reads better than 11 files of three lines each.
 */
public final class Repositories {

    private Repositories() {}

    public interface ProductRepo extends JpaRepository<Product, String> {}

    public interface NodeRepo extends JpaRepository<Node, String> {}

    public interface SupplierRepo extends JpaRepository<Supplier, String> {}

    public interface SupplierProductRepo extends JpaRepository<SupplierProduct, SupplierProduct.Key> {
        List<SupplierProduct> findBySku(String sku);
    }

    public interface InventoryRepo extends JpaRepository<Inventory, Inventory.Key> {
        Optional<Inventory> findByNodeIdAndSku(String nodeId, String sku);
    }

    public interface ForecastRepo extends JpaRepository<DemandForecast, DemandForecast.Key> {
        List<DemandForecast> findByNodeIdAndSkuAndForecastDateBetweenOrderByForecastDate(
                String nodeId, String sku, LocalDate from, LocalDate to);
    }

    public interface SalesRepo extends JpaRepository<SalesActual, SalesActual.Key> {
        List<SalesActual> findByNodeIdAndSkuAndSaleDateBetweenOrderBySaleDate(
                String nodeId, String sku, LocalDate from, LocalDate to);
    }

    public interface PromotionRepo extends JpaRepository<Promotion, Promotion.Key> {
        List<Promotion> findBySkuAndNodeIdAndEndDateGreaterThanEqual(String sku, String nodeId, LocalDate from);
    }

    public interface BudgetRepo extends JpaRepository<Budget, Budget.Key> {
        Optional<Budget> findByNodeIdAndCategoryAndPeriod(String nodeId, String category, String period);
    }

    public interface PolicyRepo extends JpaRepository<Policy, String> {}

    public interface PurchaseOrderRepo extends JpaRepository<PurchaseOrder, String> {

        Optional<PurchaseOrder> findByIdempotencyKey(String idempotencyKey);

        /**
         * Open POs for a sku at a node. Status filter lives here rather than in java
         * so we are not dragging every historical PO into memory to throw it away.
         */
        @Query("""
                select distinct po from PurchaseOrder po
                join po.lines l
                where po.nodeId = :nodeId
                  and l.sku = :sku
                  and po.status in ('SUBMITTED','CONFIRMED','PARTIALLY_CONFIRMED')
                order by po.expectedDelivery
                """)
        List<PurchaseOrder> findOpenForSku(@Param("nodeId") String nodeId, @Param("sku") String sku);

        /**
         * In-transit units arriving inside the protection window. Confirmed qty wins
         * when the supplier has responded, otherwise we count what was ordered.
         *
         * This is the number the planner checks against inventory.in_transit - if the
         * two disagree the data is inconsistent and the run escalates instead of
         * quietly ordering against a wrong position.
         */
        @Query("""
                select coalesce(sum(coalesce(l.qtyConfirmed, l.qtyOrdered)), 0)
                from PurchaseOrder po join po.lines l
                where po.nodeId = :nodeId
                  and l.sku = :sku
                  and po.status in ('SUBMITTED','CONFIRMED','PARTIALLY_CONFIRMED')
                  and po.expectedDelivery <= :cutoff
                """)
        int sumIncomingBy(@Param("nodeId") String nodeId, @Param("sku") String sku,
                          @Param("cutoff") LocalDate cutoff);
    }
}
