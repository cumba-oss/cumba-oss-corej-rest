package net.cumba.corej.rest.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InfoController}.
 *
 * <p>
 * ⚑ Regression guard: {@code /api/info} reported {@code version: "unknown"} because the controller
 * passed its SERVICE name ({@code corej-cdisc-rest}) to {@code VersionInfo.forArtifact}, which
 * matches the {@code artifactId} recorded in each jar's filtered {@code version.properties} —
 * {@code cumba-oss-corej-rest} for this module. {@code OpenApiDocTest} did not catch it: it asserts
 * the OpenAPI document's {@code info.version}, which is the hand-maintained
 * {@code OpenApiConfig.API_VERSION}, not the build version this endpoint publishes.
 * </p>
 */
class InfoControllerTest
{

    /**
     * The endpoint keeps its published service name and reports the module's real build version.
     * The exact value is the build's {@code ${revision}}, so it is asserted by shape rather than
     * literally: anything but the {@code "unknown"} fallback, starting with a digit.
     */
    @Test
    void infoReportsTheServiceNameAndTheRealBuildVersion()
    {
        InfoController.Info info = new InfoController().info();

        // ⛔ do-not-rename: spring.application.name, asserted by the sibling web module too.
        assertThat(info.service()).isEqualTo("corej-cdisc-rest");
        assertThat(info.version()).as("a resolved Maven version, not the 'unknown' fallback")
                .isNotBlank().isNotEqualTo("unknown").matches("\\d.*");
    }
}
