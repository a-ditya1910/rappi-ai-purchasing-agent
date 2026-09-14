package com.rappi.buyer.repo;

import com.rappi.buyer.domain.Budget;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BudgetRepo extends JpaRepository<Budget, Budget.Key> {

    Optional<Budget> findByNodeIdAndCategoryAndPeriod(String nodeId, String category, String period);
}
