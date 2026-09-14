package com.rappi.buyer.repo;

import com.rappi.buyer.domain.Inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InventoryRepo extends JpaRepository<Inventory, Inventory.Key> {

    Optional<Inventory> findByNodeIdAndSku(String nodeId, String sku);
}
