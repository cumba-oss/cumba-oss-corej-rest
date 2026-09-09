package net.cumba.corej.rest.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tests {@link UrlFileFetcher} against a local {@link HttpServer}. The download cap is set very low
 * ({@code max-download-bytes=20}) so the oversize path is exercised deterministically.
 */
@SpringBootTest
class UrlFileFetcherTest
{

    @TempDir
    static Path stagingBase;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("corej.sessions.dir", () -> stagingBase.resolve("sessions").toString());
        registry.add("corej.sessions.max-download-bytes", () -> "20");
    }

    @Autowired
    private UrlFileFetcher fetcher;

    @Autowired
    private SessionRegistry sessions;

    private HttpServer server;

    private String base;

    @BeforeEach
    void startServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serve("/data.csv", 200, "a,b,c\n1,2\n"); // 10 bytes
        serve("/big", 200, "x".repeat(100)); // over the 20-byte cap
        serve("/missing", 404, "nope");
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }


    @AfterEach
    void stopServer()
    {
        server.stop(0);
    }


    private void serve(String path, int status, String body)
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


    @Test
    void fetchDownloadsAndStagesFile() throws IOException
    {
        Session session = sessions.create();

        Session.FileEntry entry = fetcher.fetch(session.id(), base + "/data.csv", null).entry();

        assertThat(entry.filename()).isEqualTo("data.csv");
        assertThat(entry.size()).isEqualTo(10L);
        assertThat(session.hasFile("data.csv")).isTrue();
    }


    @Test
    void fetchHonoursExplicitFilename() throws IOException
    {
        Session session = sessions.create();

        Session.FileEntry entry = fetcher.fetch(session.id(), base + "/data.csv", "renamed.csv")
                .entry();

        assertThat(entry.filename()).isEqualTo("renamed.csv");
        assertThat(session.hasFile("renamed.csv")).isTrue();
    }


    @Test
    void fetchRejectsNonHttpScheme()
    {
        Session session = sessions.create();
        assertThatThrownBy(() -> fetcher.fetch(session.id(), "ftp://example.com/x.csv", null))
                .isInstanceOf(InvalidUrlException.class).hasMessageContaining("http");
    }


    @Test
    void fetchRejectsNon2xxResponse()
    {
        Session session = sessions.create();
        assertThatThrownBy(() -> fetcher.fetch(session.id(), base + "/missing", null))
                .isInstanceOf(InvalidUrlException.class).hasMessageContaining("404");
    }


    @Test
    void fetchAbortsOversizeDownload()
    {
        Session session = sessions.create();
        assertThatThrownBy(() -> fetcher.fetch(session.id(), base + "/big", "big.bin"))
                .isInstanceOf(DownloadTooLargeException.class);
        // The partial file must have been removed — from the session manifest...
        assertThat(session.hasFile("big.bin")).isFalse();
        // ...and from disk (no orphan left behind).
        assertThat(Files.exists(session.directory().resolve("big.bin"))).isFalse();
    }
}
