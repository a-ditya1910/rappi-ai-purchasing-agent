package com.rappi.buyer.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Everything that needs "today" injects this. Pinned to SIM_DATE so the demo
 * and the eval suite give the same answers tomorrow as they do today.
 */
@Configuration
public class ClockConfig {

    @Bean
    @Primary
    Clock clock(@Value("${app.sim-date}") String simDate) {
        return Clock.fixed(LocalDate.parse(simDate).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }

    /**
     * Real time, for audit timestamps only - when a run started, when an approval
     * was raised, when a trace step happened. These used to come from the fixed
     * clock above, so every approval had the same timestamp: the queue order was
     * random and the 24 hour expiry could never fire, because "now" never moved.
     * Business dates (deliveries, forecasts, "today") stay on the simulated clock.
     */
    @Bean
    Clock wallClock() {
        return Clock.systemUTC();
    }
}
