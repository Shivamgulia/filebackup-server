package dev.shivamgulia.filebackup.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html; login first, then Authorize with the returned JWT. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .info(new io.swagger.v3.oas.models.info.Info()
                        .title("FileBackup")
                        .description("Self-hosted file backup service. All /api/** paths except login and health need a JWT — POST /api/auth/login, then use Authorize."));
    }
}
