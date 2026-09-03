package net.cumba.corej.rest.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Top-level OpenAPI document metadata (title, version, description) surfaced in the spec + UI. */
@Configuration
public class OpenApiConfig
{

    /**
     * Stable API contract version, independent of the build/artifact version (which is reported by
     * {@code /api/info}). Bump this only on a deliberate, contract-affecting API change.
     */
    public static final String API_VERSION = "1.0";

    @Bean
    public OpenAPI corejOpenApi()
    {
        return new OpenAPI().info(new Info().title("corej CDISC Validation API")
                .version(API_VERSION)
                .description("Session-oriented REST API over the corej CDISC validation engine: "
                        + "create a session, upload study files, start a check run, poll its "
                        + "status, and page through findings.")
                .license(new License().name("AGPL-3.0-only")
                        .url("https://www.gnu.org/licenses/agpl-3.0.txt")));
    }
}
