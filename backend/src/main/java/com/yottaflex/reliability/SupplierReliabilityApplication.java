package com.yottaflex.reliability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SupplierReliabilityApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupplierReliabilityApplication.class, args);
    }
}
