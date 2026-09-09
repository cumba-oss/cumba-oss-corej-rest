package net.cumba.corej.rest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.cumba.corej.rest.run.CheckRun;
import net.cumba.corej.rest.run.CheckRunRequest;
import net.cumba.corej.rest.run.RunRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End-to-end MVC tests for the session endpoints (real registry + advice + run guard). */
@SpringBootTest
@AutoConfigureMockMvc
class SessionApiTest
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

    /** Real run registry — it is the {@link net.cumba.corej.rest.session.SessionRunGuard}. */
    @Autowired
    private RunRegistry runRegistry;

    /** Create a session and return its id, taken from the {@code Location} header. */
    private String createSession() throws Exception
    {
        String location = mvc.perform(post("/api/sessions")).andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }


    private static MockMultipartFile filePart(String content)
    {
        return new MockMultipartFile("file", "original.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }


    @Test
    void createSessionReturns201WithIdAndLocation() throws Exception
    {
        mvc.perform(post("/api/sessions")).andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.sessionId").isNotEmpty());
    }


    @Test
    void uploadReturns201WithStoredMetadata() throws Exception
    {
        String id = createSession();
        mvc.perform(multipart("/api/sessions/{id}/files", id).file(filePart("row1,row2"))
                .param("filename", "DM.csv")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(id))
                .andExpect(jsonPath("$.filename").value("DM.csv"))
                .andExpect(jsonPath("$.size").value(9))
                // F-rest-02: the reference-expansion fields are on the wire for every upload; a
                // plain file expands nothing, and says so rather than omitting the question.
                .andExpect(jsonPath("$.stagedReferences", empty()))
                .andExpect(jsonPath("$.skippedReferences", empty()));
    }

    /** A Define-XML referencing one reachable and one 404 dataset. */
    private static final String DEFINE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ODM ODMVersion="1.3.2">
              <Study OID="S1">
                <MetaDataVersion OID="MDV1" Name="study" DefineVersion="2.0.0">
                  <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM"><leaf ID="LF.DM" href="dm.xpt"/></ItemGroupDef>
                  <ItemGroupDef OID="IG.X" Name="X" Domain="X"><leaf ID="LF.X" href="missing.xpt"/></ItemGroupDef>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    private static void serve(HttpServer server, String path, int status, String body)
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange ->
        {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(bytes);
            }
        });
    }


    /**
     * F-rest-02 at the API boundary. A Define-XML whose referenced datasets cannot all be
     * downloaded leaves the session holding a PARTIAL study, and the 201 used to describe only the
     * define itself — indistinguishable from a complete staging, with the skip visible nowhere but
     * a server-side log line. The response now names what did and did not make it in.
     */
    @Test
    void uploadFromUrlReportsDefineReferencesItCouldNotStage() throws Exception
    {
        HttpServer server = HttpServer
                .create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serve(server, "/define.xml", 200, DEFINE_XML);
        serve(server, "/dm.xpt", 200, "dm-data");
        serve(server, "/missing.xpt", 404, "nope");
        server.start();
        try
        {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String id = createSession();
            mvc.perform(post("/api/sessions/{id}/files/from-url", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"" + base + "/define.xml\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.filename").value("define.xml"))
                    .andExpect(jsonPath("$.stagedReferences", contains("dm.xpt")))
                    .andExpect(jsonPath("$.skippedReferences[0].url").value(base + "/missing.xpt"))
                    .andExpect(jsonPath("$.skippedReferences[0].reason").exists());
        }
        finally
        {
            server.stop(0);
        }
    }


    @Test
    void uploadFromUrlRejectsNonHttpSchemeWith400() throws Exception
    {
        String id = createSession();
        mvc.perform(post("/api/sessions/{id}/files/from-url", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"ftp://example.com/data.csv\"}"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void duplicateFilenameReturns409() throws Exception
    {
        String id = createSession();
        mvc.perform(multipart("/api/sessions/{id}/files", id).file(filePart("a")).param("filename",
                "DM.csv")).andExpect(status().isCreated());
        mvc.perform(multipart("/api/sessions/{id}/files", id).file(filePart("b")).param("filename",
                "DM.csv")).andExpect(status().isConflict());
    }


    @Test
    void filenameWithPathSeparatorReturns400() throws Exception
    {
        String id = createSession();
        mvc.perform(multipart("/api/sessions/{id}/files", id).file(filePart("x")).param("filename",
                "../evil.csv")).andExpect(status().isBadRequest());
    }


    @Test
    void uploadToUnknownSessionReturns404() throws Exception
    {
        mvc.perform(multipart("/api/sessions/{id}/files", UUID.randomUUID().toString())
                .file(filePart("x")).param("filename", "DM.csv")).andExpect(status().isNotFound());
    }


    @Test
    void deleteReturns204() throws Exception
    {
        String id = createSession();
        mvc.perform(delete("/api/sessions/{id}", id)).andExpect(status().isNoContent());
    }


    @Test
    void deleteUnknownSessionReturns404() throws Exception
    {
        mvc.perform(delete("/api/sessions/{id}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }


    @Test
    void listSessionsIncludesCreatedWithFileCount() throws Exception
    {
        String idEmpty = createSession();
        String idWithFile = createSession();
        mvc.perform(multipart("/api/sessions/{id}/files", idWithFile).file(filePart("x"))
                .param("filename", "DM.csv")).andExpect(status().isCreated());

        String selectFile = "$[?(@.sessionId=='" + idWithFile + "')].files[0]";
        mvc.perform(get("/api/sessions")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sessionId", hasItems(idEmpty, idWithFile)))
                .andExpect(
                        jsonPath("$[?(@.sessionId=='" + idWithFile + "')].fileCount", contains(1)))
                .andExpect(jsonPath(selectFile + ".filename", contains("DM.csv")))
                .andExpect(jsonPath(selectFile + ".sizeBytes", contains(1)))
                .andExpect(jsonPath(selectFile + ".uploadedAt").isNotEmpty()).andExpect(
                        jsonPath("$[?(@.sessionId=='" + idEmpty + "')].files", contains(empty())));
    }

    // ------------------------------------------------------------------
    // naming + ordering
    // ------------------------------------------------------------------


    @Test
    void createWithNameReturnsNamedSession() throws Exception
    {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"My study\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.name").value("My study"));
    }


    @Test
    void createWithoutBodyIsUnnamed() throws Exception
    {
        mvc.perform(post("/api/sessions")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(nullValue()));
    }


    @Test
    void createWithTooLongNameReturns400() throws Exception
    {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void createWithNameListsWithThatName() throws Exception
    {
        String location = mvc
                .perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Listed study\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        String id = location.substring(location.lastIndexOf('/') + 1);
        mvc.perform(get("/api/sessions")).andExpect(status().isOk()).andExpect(
                jsonPath("$[?(@.sessionId=='" + id + "')].name", contains("Listed study")));
    }


    @Test
    void renameSetsChangesAndClearsName() throws Exception
    {
        String id = createSession();
        mvc.perform(patch("/api/sessions/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alpha\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(id))
                .andExpect(jsonPath("$.name").value("Alpha"));
        mvc.perform(patch("/api/sessions/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Beta\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Beta"));
        // Blank clears the name back to unnamed.
        mvc.perform(patch("/api/sessions/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(nullValue()));
    }


    @Test
    void renameUnknownSessionReturns404() throws Exception
    {
        mvc.perform(patch("/api/sessions/{id}", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
    }


    @Test
    void renameWithInvalidNameReturns400() throws Exception
    {
        String id = createSession();
        mvc.perform(patch("/api/sessions/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void listSessionsAreNewestFirst() throws Exception
    {
        // Create a few sessions; the listing must come back sorted by createdAt descending.
        createSession();
        createSession();
        createSession();
        String json = mvc.perform(get("/api/sessions")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        List<String> createdAt = new ArrayList<>();
        for (JsonNode node : JsonMapper.builder().build().readTree(json))
        {
            createdAt.add(node.get("createdAt").asString());
        }
        // ISO-8601 strings sort lexicographically = chronologically; newest first.
        assertThat(createdAt).hasSizeGreaterThanOrEqualTo(3)
                .isSortedAccordingTo(Comparator.reverseOrder());
    }


    @Test
    void deleteWhileRunInFlightReturns409() throws Exception
    {
        String id = createSession();
        // A freshly-registered run is PENDING (in flight) and never completes here, so the guard
        // must veto the delete.
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null, null, null,
                null);
        runRegistry.register(new CheckRun(UUID.randomUUID().toString(), id, req));
        mvc.perform(delete("/api/sessions/{id}", id)).andExpect(status().isConflict());
    }


    @Test
    void deleteFileReturns204AndRemovesIt() throws Exception
    {
        String id = createSession();
        mvc.perform(multipart("/api/sessions/{id}/files", id).file(filePart("x")).param("filename",
                "DM.csv")).andExpect(status().isCreated());

        mvc.perform(delete("/api/sessions/{id}/files/{filename}", id, "DM.csv"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/sessions")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId=='" + id + "')].fileCount", contains(0)));
    }


    @Test
    void deleteUnknownFileReturns404() throws Exception
    {
        String id = createSession();
        mvc.perform(delete("/api/sessions/{id}/files/{filename}", id, "missing.csv"))
                .andExpect(status().isNotFound());
    }


    @Test
    void deleteFileFromUnknownSessionReturns404() throws Exception
    {
        mvc.perform(delete("/api/sessions/{id}/files/{filename}", UUID.randomUUID().toString(),
                "DM.csv")).andExpect(status().isNotFound());
    }


    @Test
    void deleteAllFilesReturns204AndClearsThem() throws Exception
    {
        String id = createSession();
        mvc.perform(multipart("/api/sessions/{id}/files", id).file(filePart("a")).param("filename",
                "A.csv")).andExpect(status().isCreated());
        mvc.perform(multipart("/api/sessions/{id}/files", id).file(filePart("b")).param("filename",
                "B.csv")).andExpect(status().isCreated());

        mvc.perform(delete("/api/sessions/{id}/files", id)).andExpect(status().isNoContent());

        mvc.perform(get("/api/sessions")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId=='" + id + "')].fileCount", contains(0)));
    }


    @Test
    void deleteFileWhileRunInFlightReturns409() throws Exception
    {
        String id = createSession();
        mvc.perform(multipart("/api/sessions/{id}/files", id).file(filePart("x")).param("filename",
                "DM.csv")).andExpect(status().isCreated());
        CheckRunRequest req = new CheckRunRequest(null, null, null, null, null, null, null, null,
                null);
        runRegistry.register(new CheckRun(UUID.randomUUID().toString(), id, req));

        mvc.perform(delete("/api/sessions/{id}/files/{filename}", id, "DM.csv"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/sessions/{id}/files", id)).andExpect(status().isConflict());
    }

    private static final String DEFINE_V21 = "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\""
            + " xmlns:def=\"http://www.cdisc.org/ns/def/v2.1\" ODMVersion=\"1.3.2\""
            + " FileType=\"Snapshot\" FileOID=\"X\" def:Context=\"Other\"><Study OID=\"S\">"
            + "<MetaDataVersion OID=\"M\" Name=\"m\" def:DefineVersion=\"2.1.0\"/></Study></ODM>";

    @Test
    void defineVersionDetectsV21() throws Exception
    {
        String id = createSession();
        mvc.perform(multipart("/api/sessions/{id}/files", id)
                .file(new MockMultipartFile("file", "define.xml", "application/xml",
                        DEFINE_V21.getBytes(StandardCharsets.UTF_8)))
                .param("filename", "define.xml")).andExpect(status().isCreated());

        mvc.perform(get("/api/sessions/{id}/files/{filename}/define-version", id, "define.xml"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("2.1"))
                .andExpect(jsonPath("$.defineVersion").value("2.1.0"));
    }


    @Test
    void defineVersionNullForNonDefineFile() throws Exception
    {
        String id = createSession();
        mvc.perform(multipart("/api/sessions/{id}/files", id).file(filePart("row1,row2"))
                .param("filename", "DM.csv")).andExpect(status().isCreated());

        mvc.perform(get("/api/sessions/{id}/files/{filename}/define-version", id, "DM.csv"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(nullValue()))
                .andExpect(jsonPath("$.defineVersion").value(nullValue()));
    }


    @Test
    void defineVersionUnknownFileReturns404() throws Exception
    {
        String id = createSession();
        mvc.perform(get("/api/sessions/{id}/files/{filename}/define-version", id, "nope.xml"))
                .andExpect(status().isNotFound());
    }
}
