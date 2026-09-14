package com.rappi.buyer.repo;

import com.rappi.buyer.domain.DemandForecast;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ForecastRepo extends JpaRepository<DemandForecast, DemandForecast.Key> {

    List<DemandForecast> findByNodeIdAndSkuAndForecastDateBetweenOrderByForecastDate(
            String nodeId, String sku, LocalDate from, LocalDate to);
}
