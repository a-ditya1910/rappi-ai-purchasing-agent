package com.rappi.buyer.repo;

import com.rappi.buyer.domain.PurchaseOrder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepo extends JpaRepository<PurchaseOrder, String> {

    Optional<PurchaseOrder> findByIdempotencyKey(String idempotencyKey);

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
