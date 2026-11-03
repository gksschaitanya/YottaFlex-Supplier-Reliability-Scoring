package com.yottaflex.reliability.supplier.api;

import com.yottaflex.reliability.supplier.api.SupplierListResponse.SupplierSummary;
import com.yottaflex.reliability.supplier.application.SupplierQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupplierControllerTest {
    private SupplierQueryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SupplierQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SupplierController(service)).build();
    }

    @Test
    void listsPersistedSupplierAndLatestScoreFieldsWithPagination() throws Exception {
        when(service.list(0,20)).thenReturn(new SupplierListResponse(
                List.of(new SupplierSummary("SUP-000001","Northwind Components","ACTIVE",
                        new BigDecimal("87.50"),"GOOD","SR-abc123")),
                0,20,1,1,true,true));

        mvc.perform(get("/api/v1/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].supplierId").value("SUP-000001"))
                .andExpect(jsonPath("$.content[0].name").value("Northwind Components"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].score").value(87.50))
                .andExpect(jsonPath("$.content[0].classification").value("GOOD"))
                .andExpect(jsonPath("$.content[0].scoreRunId").value("SR-abc123"))
                .andExpect(jsonPath("$.totalElements").value(1));
        verify(service).list(0,20);
    }

    @Test
    void exposesNoScoreAsNullInsteadOfInventingOne() throws Exception {
        when(service.list(0,20)).thenReturn(new SupplierListResponse(
                List.of(new SupplierSummary("SUP-000002","Example Supplier","UNDER_REVIEW",null,null,null)),
                0,20,1,1,true,true));

        mvc.perform(get("/api/v1/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].score").value(nullValue()))
                .andExpect(jsonPath("$.content[0].classification").value(nullValue()));
    }
}
