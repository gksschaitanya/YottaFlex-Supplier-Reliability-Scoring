package com.yottaflex.reliability.supplier.api;

import com.yottaflex.reliability.supplier.application.SupplierQueryService;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {
    private final SupplierQueryService suppliers;

    public SupplierController(SupplierQueryService suppliers) {
        this.suppliers = suppliers;
    }

    @GetMapping
    public SupplierListResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        return suppliers.list(page, size);
    }
}
