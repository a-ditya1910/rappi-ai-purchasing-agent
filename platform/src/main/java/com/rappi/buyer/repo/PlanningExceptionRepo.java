package com.rappi.buyer.repo;

import com.rappi.buyer.domain.PlanningException;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanningExceptionRepo extends JpaRepository<PlanningException, Long> {

    List<PlanningException> findByBatchIdOrderById(String batchId);

    Optional<PlanningException> findTopByOrderByIdDesc();
}
