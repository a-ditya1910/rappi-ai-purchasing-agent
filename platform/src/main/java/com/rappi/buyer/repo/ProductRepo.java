package com.rappi.buyer.repo;

import com.rappi.buyer.domain.Product;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepo extends JpaRepository<Product, String> {}
