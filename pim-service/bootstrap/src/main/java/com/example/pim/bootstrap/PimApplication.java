package com.example.pim.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The only place that knows every module: it plugs adapters into the application's ports. */
@SpringBootApplication(scanBasePackages = {"com.example.pim.bootstrap", "com.example.pim.adapter"})
@ConfigurationPropertiesScan("com.example.pim.adapter")
@EnableScheduling
public class PimApplication {

    public static void main(String[] args) {
        SpringApplication.run(PimApplication.class, args);
    }
}
