package com.rappi.buyer.repo;

import com.rappi.buyer.domain.Supplier;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepo extends JpaRepository<Supplier, String> {}
