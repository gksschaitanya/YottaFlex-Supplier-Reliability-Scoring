package com.yottaflex.reliability.generation.application;

import java.time.LocalDate;

import com.yottaflex.reliability.generation.domain.DatasetScale;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "platform.generation")
public class SyntheticGenerationProperties {

    private long seed = 20261022L;

    @NotNull
    private LocalDate referenceDate = LocalDate.of(2026, 10, 22);

    @NotBlank
    private String generatorVersion = "1.0.0";

    @NotNull
    private DatasetScale defaultScale = DatasetScale.STARTER;

    @Min(1)
    private int batchSize = 500;

    private boolean enabled = true;

    @Valid
    @NotNull
    private Starter starter = new Starter();

    @Min(1)
    @Max(10_000)
    private int maximumBatchSize = 2_000;

    @Getter
    @Setter
    public static class Starter {

        @Min(1)
        private int suppliers = 25;

        @Min(1)
        private int purchaseOrders = 120;

        @Min(1)
        private int shipments = 180;
    }
}
