package com.rappi.buyer.repo;

import com.rappi.buyer.domain.PurchaseOrder;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepo extends JpaRepository<PurchaseOrder, String> {

    Optional<PurchaseOrder> findByIdempotencyKey(String idempotencyKey);

    /**
     * Unit prices we have actually paid for this sku, newest first. The price
     * variance check compares against the first one.
     *
     * No separate price history table - the PO lines already are the history, and
     * a second copy would only be one more thing to keep in sync.
     */
    @Query("""
            select l.unitPrice from PurchaseOrder po
            join po.lines l
            where po.nodeId = :nodeId
              and l.sku = :sku
              and po.status <> 'CANCELLED'
            order by po.createdAt desc
            """)
    List<BigDecimal> lastPaidPrices(@Param("nodeId") String nodeId, @Param("sku") String sku,
                                    Pageable page);

    default List<BigDecimal> lastPaidPrices(String nodeId, String sku) {
        return lastPaidPrices(nodeId, sku, Pageable.ofSize(1));
    }

    /**
     * Open POs for a sku at a node. The status filter lives here rather than in
     * java so we are not dragging every historical PO into memory to discard it.
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
     * In-transit units arriving inside the protection window. Confirmed quantity
     * wins when the supplier has responded, otherwise we count what was ordered.
     *
     * This is the number the planner checks against inventory.in_transit. If the
     * two disagree the data is inconsistent and the run escalates instead of
     * quietly ordering against a position we cannot trust.
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
