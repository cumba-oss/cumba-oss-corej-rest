package net.cumba.corej.rest.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import net.cumba.corej.rest.report.ReportStore;
import net.cumba.corej.rest.run.CheckRun;
import net.cumba.corej.rest.run.CheckRunRequest;
import net.cumba.corej.rest.run.CheckRunner;
import net.cumba.corej.rest.run.RunRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies Phase 8 HTTP response compression: a large report served by {@code /report} (and
 * {@code /report-v2}) carries {@code Content-Encoding: gzip} when the client sends
 * {@code Accept-Encoding: gzip}. Runs against a real embedded server on a random port and uses the
 * JDK {@link HttpClient}, which (unlike RestTemplate/HttpURLConnection) neither auto-adds the
 * request header nor auto-decompresses the response — so the encoding is observable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportCompressionIntegrationTest
{

    @TempDir
    static Path stagingBase;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("corej.sessions.dir", () -> stagingBase.resolve("sessions").toString());
        registry.add("corej.reports.dir", () -> stagingBase.resolve("reports").toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RunRegistry runRegistry;

    @Autowired
    private ReportStore reportStore;

    @MockitoBean
    private CheckRunner runner;

    private static CheckRunRequest minimalRequest()
    {
        return new CheckRunRequest(null, null, null, null, null, null, null, null, null);
    }


    @Test
    void largeReportIsGzippedWhenClientAcceptsGzip() throws Exception
    {
        CheckRun run = new CheckRun(UUID.randomUUID().toString(), "sess-z", minimalRequest());
        run.markSucceeded(null);
        runRegistry.register(run);
        reportStore.persist(run.id(), largeReport());
        reportStore.persistV2(run.id(), largeReportV2());

        assertGzip("/api/checks/" + run.id() + "/report", "Conformance_Details");
        assertGzip("/api/checks/" + run.id() + "/report-v2", "Report_Version");
    }


    private void assertGzip(String path, String expectedToken) throws Exception
    {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<byte[]> response = client
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Accept-Encoding", "gzip").header("Accept", "application/json")
                        .GET().build(), BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(response.body())))
        {
            String body = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains(expectedToken);
        }
    }


    /** A v1 report comfortably larger than the 4 KB compression threshold. */
    private static String largeReport()
    {
        StringBuilder details = new StringBuilder("[");
        for (int i = 0; i < 200; i++)
        {
            if (i > 0)
            {
                details.append(',');
            }
            details.append("{\"core_id\":\"CORE-").append(i).append("\",\"message\":\"a fairly ")
                    .append("long finding message used to pad the report past 4KB\",")
                    .append("\"executability\":\"executable\",\"dataset\":\"dm.xpt\",")
                    .append("\"domain\":\"DM\",\"USUBJID\":\"SUBJ-").append(i).append("\",\"row\":")
                    .append(i + 1).append(",\"SEQ\":\"").append(i)
                    .append("\",\"variables\":[\"AGE\"],\"values\":[\"v").append(i).append("\"]}");
        }
        details.append(']');
        return "{\"Conformance_Details\":{\"Standard\":\"SDTMIG\"},\"Dataset_Details\":[],"
                + "\"Issue_Summary\":[],\"Issue_Details\":" + details + ",\"Rules_Report\":[]}";
    }


    /** A v2 report comfortably larger than the 4 KB compression threshold. */
    private static String largeReportV2()
    {
        StringBuilder findings = new StringBuilder("[");
        for (int i = 0; i < 200; i++)
        {
            if (i > 0)
            {
                findings.append(',');
            }
            findings.append("{\"core_id\":\"CORE-").append(i)
                    .append("\",\"message\":\"a fairly long finding message used to pad the v2 ")
                    .append("report past 4KB\",\"executability\":\"executable\",")
                    .append("\"dataset\":\"dm.xpt\",\"domain\":\"DM\",")
                    .append("\"location\":{\"dataset\":\"DM\",\"variables\":[\"AGE\"]},")
                    .append("\"variables\":[\"AGE\"],\"rows\":[{\"row\":").append(i + 1)
                    .append(",\"USUBJID\":\"SUBJ-").append(i).append("\",\"SEQ\":\"").append(i)
                    .append("\",\"values\":[\"v").append(i).append("\"]}]}");
        }
        findings.append(']');
        return "{\"Report_Version\":\"2.0\",\"Conformance_Details\":{\"Standard\":\"SDTMIG\"},"
                + "\"Dataset_Details\":[],\"Issue_Summary\":[],\"Findings\":" + findings
                + ",\"Rules_Report\":[]}";
    }
}
