package com.rappi.buyer.tools;

import com.rappi.buyer.domain.Product;
import com.rappi.buyer.domain.Supplier;
import com.rappi.buyer.domain.SupplierProduct;
import com.rappi.buyer.repo.ProductRepo;
import com.rappi.buyer.repo.SupplierProductRepo;
import com.rappi.buyer.repo.SupplierRepo;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Product and supplier master data, read on nearly every agent run and changed
 * almost never - the textbook case for a cache. Kept in redis for 60 seconds.
 *
 * The one thing that does change is a supplier's reliability, when goods are
 * received. That evicts the supplier cache, after the receipt commits -
 * otherwise the agent could read a stale score for up to a minute and rank a
 * supplier that just delivered late as if it had not.
 *
 * Plain ArrayList and LinkedHashMap on purpose: the json serializer writes the
 * class name next to the value, and List.of / Stream.toList give immutable
 * classes it cannot rebuild when reading back.
 */
@Service
public class MasterData {

    private final ProductRepo products;
    private final SupplierRepo suppliers;
    private final SupplierProductRepo supplierProducts;

    public MasterData(ProductRepo products, SupplierRepo suppliers, SupplierProductRepo supplierProducts) {
        this.products = products;
        this.suppliers = suppliers;
        this.supplierProducts = supplierProducts;
    }

    @Cacheable(value = "product", key = "#sku")
    public Map<String, Object> product(String sku) {
        Product p = products.findById(sku).orElseThrow(() -> {
            List<String> known = new ArrayList<>(products.findAll().stream().map(Product::getSku).limit(10).toList());
            return new ToolController.NotFound("SKU_NOT_FOUND", "unknown sku " + sku, known);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sku", p.getSku());
        out.put("name", p.getName());
        out.put("category", p.getCategory());
        out.put("casePack", p.getCasePack());
        out.put("unitVolumeCm3", p.getUnitVolumeCm3());
        out.put("shelfLifeDays", p.getShelfLifeDays());
        out.put("isPerishable", p.isPerishable());
        out.put("abcClass", p.getAbcClass());
        return out;
    }

    @Cacheable(value = "suppliers", key = "#sku")
    public List<Map<String, Object>> suppliers(String sku) {
        List<SupplierProduct> offers = supplierProducts.findBySku(sku);
        if (offers.isEmpty()) {
            throw new ToolController.NotFound("NO_SUPPLIERS", "no supplier supplies " + sku);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupplierProduct sp : offers) {
            Supplier s = suppliers.findById(sp.getSupplierId()).orElseThrow();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supplierId", s.getId());
            m.put("name", s.getName());
            m.put("unitPrice", sp.getUnitPrice());
            m.put("moqUnits", Math.max(s.getMoqUnits(), sp.getMinOrderUnits()));
            m.put("leadTimeDays", s.getLeadTimeDays());
            m.put("leadTimeStd", s.getLeadTimeStd());
            m.put("maxDailyCapacity", sp.getMaxDailyCapacity());
            m.put("reliabilityScore", s.getReliabilityScore());
            m.put("isActive", s.isActive());
            out.add(m);
        }
        return out;
    }

    @CacheEvict(value = "suppliers", allEntries = true)
    public void suppliersChanged() {
    }
}
