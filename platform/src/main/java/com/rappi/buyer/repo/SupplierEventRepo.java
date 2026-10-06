package com.rappi.buyer.repo;

import com.rappi.buyer.domain.SupplierEvent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SupplierEventRepo extends JpaRepository<SupplierEvent, Long> {

    Optional<SupplierEvent> findByPoIdAndTypeAndPayloadHash(String poId, String type, String payloadHash);
}
