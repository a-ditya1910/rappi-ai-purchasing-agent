package com.rappi.buyer.repo;

import com.rappi.buyer.domain.Policy;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRepo extends JpaRepository<Policy, String> {}
