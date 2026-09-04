package net.cumba.corej.rest.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import net.cumba.corej.core.VersionInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal service-info endpoint. Primarily a foundation smoke test that the Spring MVC + springdoc
 * (OpenAPI / Swagger UI) stack is wired up; the real validation endpoints arrive in the session /
 * check controllers.
 */
@RestController
@RequestMapping("/api/info")
@Tag(name = "info", description = "Service metadata")
public class InfoController
{

    private static final String SERVICE = "corej-cdisc-rest";

    /**
     * This module's Maven artifact id — the key {@link VersionInfo#forArtifact(String)} matches
     * against the {@code artifactId} recorded in each jar's filtered {@code version.properties}.
     *
     * <p>
     * ⛔ Deliberately <b>not</b> {@link #SERVICE}: the service name is the published
     * {@code spring.application.name} and is unrelated to the Maven coordinates. Passing it here
     * matched no {@code version.properties} on the classpath, so the endpoint reported
     * {@code version: "unknown"}. This constant must track {@code <artifactId>} in this module's
     * pom; it is independent of {@code ${revision}}, so a version bump cannot break it.
     * </p>
     */
    private static final String ARTIFACT_ID = "cumba-oss-corej-rest";

    private static final String VERSION = VersionInfo.forArtifact(ARTIFACT_ID).version();

    /**
     * Service identity + version. Used as a liveness probe and an OpenAPI smoke endpoint. The
     * version is the build's {@code ${revision}}, read from the module's
     * {@code version.properties}.
     */
    @GetMapping
    @Operation(summary = "Return service name and version")
    public Info info()
    {
        return new Info(SERVICE, VERSION);
    }

    /**
     * Service identity payload.
     *
     * <p>
     * Error Prone's {@code AvoidCommonTypeNames} objects that Info clashes with
     * {@code java.lang.ProcessHandle.Info}. Renaming it would rename the generated OpenAPI schema
     * component, which the SPA's checked-in {@code schema.d.ts} and {@code openapi.snapshot.json}
     * are compared against — a wide blast radius for a naming preference on a nested record that is
     * never imported unqualified.
     * </p>
     */
    @SuppressWarnings("AvoidCommonTypeNames")
    public record Info(String service, String version)
    {
    }
}
