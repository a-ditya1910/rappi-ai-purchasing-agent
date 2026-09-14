package com.rappi.buyer.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Everything that needs "today" injects this. Pinned to SIM_DATE so the demo
 * and the eval suite give the same answers tomorrow as they do today.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock(@Value("${app.sim-date}") String simDate) {
        return Clock.fixed(LocalDate.parse(simDate).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }
}
