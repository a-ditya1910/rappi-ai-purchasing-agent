package com.rappi.buyer.repo;

import com.rappi.buyer.domain.SalesActual;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SalesRepo extends JpaRepository<SalesActual, SalesActual.Key> {

    List<SalesActual> findByNodeIdAndSkuAndSaleDateBetweenOrderBySaleDate(
            String nodeId, String sku, LocalDate from, LocalDate to);
}
