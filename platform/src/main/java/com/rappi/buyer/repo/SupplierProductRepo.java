package com.rappi.buyer.repo;

import com.rappi.buyer.domain.SupplierProduct;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierProductRepo extends JpaRepository<SupplierProduct, SupplierProduct.Key> {

    List<SupplierProduct> findBySku(String sku);
}
