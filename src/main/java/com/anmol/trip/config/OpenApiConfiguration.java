package com.anmol.trip.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI tripManagementOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Trip Management API")
                        .version("1.0.0")
                        .description("""
                                Create, query, transition and delete trips.

                                Design highlights:
                                * Trip numbers are unique and case-insensitive.
                                * Status transitions are enforced by the lifecycle rules and cannot move backwards.
                                * Status updates are applied with a compare-and-set, so concurrent writers cannot
                                  both take effect.
                                * `POST` accepts an `Idempotency-Key` header; replays return the original trip.
                                * All errors are RFC 9457 problem documents with a stable `code` field.
                                """)
                        .license(new License().name("Apache-2.0")))
                .servers(List.of(new Server().url("/").description("Current host")))
                .components(new Components());
    }
}
