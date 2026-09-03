package net.cumba.corej.rest.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import net.cumba.corej.rest.config.OpenApiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the springdoc OpenAPI spec and Swagger UI are wired up and reachable. */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocTest
{

    @TempDir
    static Path stagingBase;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("corej.sessions.dir", () -> stagingBase.resolve("sessions").toString());
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void apiDocsExposeInfoAndPaths() throws Exception
    {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("corej CDISC Validation API"))
                .andExpect(jsonPath("$.info.version").value(OpenApiConfig.API_VERSION))
                .andExpect(jsonPath("$.paths['/api/sessions']").exists())
                .andExpect(jsonPath("$.paths['/api/checks/{id}/dataset-groups']").exists())
                .andExpect(jsonPath("$.paths['/api/checks/{id}/log/lines']").exists());
    }


    /**
     * {@code referenceDataFilenames} is deprecated on the published surface and points callers at
     * {@code datasetFilter} (wave-37 lane D). ⛔ The field is <b>not</b> removed — {@code W32-F1}
     * froze the v1 surface, so removal is a v2 question; the deprecation flag is what tells a
     * direct API client, and it is what {@code openapi-typescript} turns into the SPA's
     * {@code @deprecated} marker.
     */
    @Test
    void referenceDataFilenamesIsPublishedAsDeprecated() throws Exception
    {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath(
                "$.components.schemas.CheckRunRequest.properties.referenceDataFilenames.deprecated")
                        .value(true))
                .andExpect(jsonPath(
                        "$.components.schemas.CheckRunRequest.properties.referenceDataFilenames.description")
                                .value(org.hamcrest.Matchers.containsString("datasetFilter")));
    }


    @Test
    void swaggerUiRedirects() throws Exception
    {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }
}
