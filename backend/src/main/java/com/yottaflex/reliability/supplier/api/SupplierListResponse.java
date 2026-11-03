package com.yottaflex.reliability.supplier.api;

import java.math.BigDecimal;
import java.util.List;

public record SupplierListResponse(
        List<SupplierSummary> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public record SupplierSummary(
            String supplierId,
            String name,
            String status,
            BigDecimal score,
            String classification,
            String scoreRunId) { }
}
