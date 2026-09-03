package net.cumba.corej.rest.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tests the Define-XML expansion of {@link UrlFileFetcher}: fetching a {@code define.xml} by URL
 * also downloads every dataset its {@code ItemGroupDef} leaves reference. Uses a generous size cap
 * (the {@code define.xml} itself exceeds the tiny cap of {@link UrlFileFetcherTest}).
 */
@SpringBootTest
class UrlFileFetcherDefineTest
{

    /** A minimal Define-XML referencing dm.xpt, ae.xpt, and a missing.xpt that 404s. */
    private static final String DEFINE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ODM ODMVersion="1.3.2">
              <Study OID="S1">
                <MetaDataVersion OID="MDV1" Name="study" DefineVersion="2.0.0">
                  <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM"><leaf ID="LF.DM" href="dm.xpt"/></ItemGroupDef>
                  <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE"><leaf ID="LF.AE" href="ae.xpt"/></ItemGroupDef>
                  <ItemGroupDef OID="IG.X" Name="X" Domain="X"><leaf ID="LF.X" href="missing.xpt"/></ItemGroupDef>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    @TempDir
    static java.nio.file.Path stagingBase;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry)
    {
        registry.add("corej.sessions.dir", () -> stagingBase.resolve("sessions").toString());
        registry.add("corej.sessions.max-download-bytes", () -> "1000000");
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
        serve("/define.xml", 200, DEFINE_XML);
        serve("/dm.xpt", 200, "dm-data");
        serve("/ae.xpt", 200, "ae-data");
        serve("/missing.xpt", 404, "nope");
        serve("/notdefine.xml", 200, "this is not xml");
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
    void fetchExpandsDefineReferencedDatasets() throws IOException
    {
        Session session = sessions.create();

        Session.FileEntry entry = fetcher.fetch(session.id(), base + "/define.xml", null);

        assertThat(entry.filename()).isEqualTo("define.xml");
        // The referenced datasets that resolved are staged alongside the define.xml...
        assertThat(session.hasFile("define.xml")).isTrue();
        assertThat(session.hasFile("dm.xpt")).isTrue();
        assertThat(session.hasFile("ae.xpt")).isTrue();
        // ...and a reference that 404s is skipped without failing the define staging.
        assertThat(session.hasFile("missing.xpt")).isFalse();
    }


    @Test
    void fetchToleratesAlreadyStagedReference() throws IOException
    {
        Session session = sessions.create();
        sessions.addFile(session.id(), "dm.xpt",
                new java.io.ByteArrayInputStream("preexisting".getBytes(StandardCharsets.UTF_8)));

        fetcher.fetch(session.id(), base + "/define.xml", null);

        // The pre-existing dm.xpt is untouched (not overwritten by the define expansion)...
        assertThat(session.hasFile("dm.xpt")).isTrue();
        // ...and the remaining reference is still staged.
        assertThat(session.hasFile("ae.xpt")).isTrue();
    }


    @Test
    void unparseableDefineIsStagedAlone() throws IOException
    {
        Session session = sessions.create();

        Session.FileEntry entry = fetcher.fetch(session.id(), base + "/notdefine.xml",
                "define.xml");

        assertThat(entry.filename()).isEqualTo("define.xml");
        assertThat(session.hasFile("define.xml")).isTrue();
        assertThat(session.hasFile("dm.xpt")).isFalse();
        assertThat(session.hasFile("ae.xpt")).isFalse();
    }
}
