package net.cumba.corej.rest;

import net.cumba.corej.rest.config.CorejProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot entry point for the corej CDISC validation REST service. Exposes a session-oriented
 * validation API plus an auto-generated OpenAPI spec and Swagger UI (springdoc) at
 * {@code /swagger-ui.html}.
 */
@SpringBootApplication
@EnableConfigurationProperties(CorejProperties.class)
public class CorejRestApplication
{

    public static void main(String[] args)
    {
        SpringApplication.run(CorejRestApplication.class, args);
    }
}
