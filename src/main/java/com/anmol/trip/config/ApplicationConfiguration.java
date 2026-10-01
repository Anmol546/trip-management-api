package com.anmol.trip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Application wide clock.
 *
 * <p>Injected rather than called statically so tests can pin time, which is
 * what makes the timestamp assertions in the concurrency tests deterministic.
 */
@Configuration
public class ApplicationConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
