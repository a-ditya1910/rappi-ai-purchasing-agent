package com.rappi.buyer.repo;

import com.rappi.buyer.domain.Promotion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PromotionRepo extends JpaRepository<Promotion, Promotion.Key> {

    List<Promotion> findBySkuAndNodeIdAndEndDateGreaterThanEqual(
            String sku, String nodeId, LocalDate from);
}
