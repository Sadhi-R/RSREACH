package com.rsreach.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI rsReachOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("RSReach API")
                        .description("RSReach modular monolith API (Phase 0 foundation)")
                        .version("0.1.0"));
    }
}
