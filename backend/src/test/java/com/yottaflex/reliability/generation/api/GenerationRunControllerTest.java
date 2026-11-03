package com.yottaflex.reliability.generation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.yottaflex.reliability.generation.application.GenerationRunNotFoundException;
import com.yottaflex.reliability.generation.application.GenerationRunQueryService;
import com.yottaflex.reliability.generation.application.GenerationRunService;
import com.yottaflex.reliability.generation.domain.DatasetScale;
import com.yottaflex.reliability.generation.domain.GenerationRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class GenerationRunControllerTest {

    @Mock
    private GenerationRunService generationRunService;

    @Mock
    private GenerationRunQueryService queryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GenerationRunController controller = new GenerationRunController(generationRunService, queryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GenerationApiExceptionHandler())
                .build();
    }

    @Test
    void postReturnsAcceptedAndInitialRunStatus() throws Exception {
        GenerationRunResponse response = new GenerationRunResponse(
                "GEN-2026-000001", GenerationRunStatus.RUNNING, DatasetScale.STARTER, 20261022L,
                LocalDate.of(2026, 10, 22), "1.0.0", "config-hash", Instant.parse("2026-10-22T00:00:00Z"),
                null, null, null, Map.of("supplier", 25L), Map.of("supplier", 0L), List.of());
        when(generationRunService.start(any(GenerationRunRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/generation/runs")
                        .contentType("application/json")
                        .content("{\"scale\":\"STARTER\",\"seed\":20261022,\"referenceDate\":\"2026-10-22\",\"batchSize\":100}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("GEN-2026-000001"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.expectedRowCounts.supplier").value(25));
    }

    @Test
    void getReturnsRunNotFoundAs404() throws Exception {
        when(queryService.get("GEN-2026-000999"))
                .thenThrow(new GenerationRunNotFoundException("GEN-2026-000999"));

        mockMvc.perform(get("/api/v1/generation/runs/GEN-2026-000999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GENERATION_RUN_NOT_FOUND"));
    }
}
