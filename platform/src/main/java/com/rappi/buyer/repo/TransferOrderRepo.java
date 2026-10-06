package com.rappi.buyer.repo;

import com.rappi.buyer.domain.TransferOrder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TransferOrderRepo extends JpaRepository<TransferOrder, String> {

    Optional<TransferOrder> findByIdempotencyKey(String idempotencyKey);

    /** Transfers still on their way to a store, the same way open POs are. */
    @Query("""
            select t from TransferOrder t
            where t.toNode = :node and t.sku = :sku and t.status = 'IN_TRANSIT'
            order by t.expectedArrival
            """)
    List<TransferOrder> findIncoming(@Param("node") String node, @Param("sku") String sku);

    @Query("""
            select coalesce(sum(t.qty), 0) from TransferOrder t
            where t.toNode = :node and t.sku = :sku and t.status = 'IN_TRANSIT'
              and t.expectedArrival <= :cutoff
            """)
    int sumIncomingBy(@Param("node") String node, @Param("sku") String sku,
                      @Param("cutoff") LocalDate cutoff);

    @Query("""
            select coalesce(sum(t.qty), 0) from TransferOrder t
            where t.toNode = :node and t.sku = :sku and t.status = 'IN_TRANSIT'
            """)
    int sumOpenIncoming(@Param("node") String node, @Param("sku") String sku);
}
