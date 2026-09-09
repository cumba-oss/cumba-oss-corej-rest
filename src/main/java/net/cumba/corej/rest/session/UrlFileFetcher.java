package net.cumba.corej.rest.session;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.cumba.cdisc.define.DefineSupport;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.rest.config.CorejProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Downloads a file from an {@code http} / {@code https} URL into a session's staging directory,
 * registering it exactly like an uploaded file (so the SHA-256 + size manifest logic is shared).
 *
 * <p>
 * The download is bounded by {@code corej.sessions.max-download-bytes} (streamed and aborted if
 * exceeded — the partial file is removed) and {@code corej.sessions.download-timeout}. The JDK
 * {@link HttpClient} honours the JVM's global proxy system properties
 * ({@code http.proxyHost}/{@code https.proxyHost}/…); there is no app-level proxy or auth
 * configuration. Private/loopback hosts are not specially blocked — this is an internal tool.
 * </p>
 */
@Component
public class UrlFileFetcher
{

    private static final Logger LOG = LoggerFactory.getLogger(UrlFileFetcher.class);

    private final SessionRegistry registry;

    private final long maxBytes;

    private final Duration timeout;

    private final HttpClient httpClient;

    public UrlFileFetcher(SessionRegistry registry, CorejProperties properties)
    {
        this.registry = registry;
        this.maxBytes = properties.getSessions().getMaxDownloadBytes();
        this.timeout = properties.getSessions().getDownloadTimeout();
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }


    /**
     * Fetches {@code url} into the session and stages it.
     *
     * @param sessionId
     *            the target session
     * @param url
     *            an {@code http} / {@code https} URL
     * @param filename
     *            the bare name to store under; when blank the name is derived from the URL path
     * @return the staged file entry (with size + SHA-256) together with the outcome of any
     *         Define-XML reference expansion
     * @throws InvalidUrlException
     *             on a malformed URL, a disallowed scheme, or a non-2xx response
     * @throws DownloadTooLargeException
     *             when the response exceeds the configured size cap
     * @throws IOException
     *             on a transport or storage failure
     */
    public FetchResult fetch(String sessionId, String url, String filename) throws IOException
    {
        URI uri = parse(url);
        String name = filename != null && !filename.isBlank() ? filename : deriveFilename(uri);
        Session.FileEntry entry = download(sessionId, uri, name);
        if (isDefineXml(name))
        {
            return expandDefineReferences(sessionId, uri, entry);
        }
        return FetchResult.plain(entry);
    }


    /**
     * Download {@code uri} into the session under {@code name} (status check + size cap + stage).
     */
    private Session.FileEntry download(String sessionId, URI uri, String name) throws IOException
    {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        HttpResponse<InputStream> response;
        try
        {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted for " + uri, e);
        }
        try (InputStream body = response.body())
        {
            if (response.statusCode() / 100 != 2)
            {
                throw new InvalidUrlException(
                        "Remote returned HTTP " + response.statusCode() + " for " + uri);
            }
            try (InputStream capped = new LimitedInputStream(body, maxBytes))
            {
                return registry.addFile(sessionId, name, capped);
            }
        }
    }


    /**
     * When {@code define} is a Define-XML, additionally download every dataset its
     * {@code ItemGroupDef} leaves reference. Relative {@code href}s are resolved against the
     * original remote {@code defineUri} (not the local staging path), so sibling-relative
     * references work. Best-effort: a referenced dataset that fails to download or exceeds the size
     * cap is skipped — it never fails the define.xml staging itself.
     *
     * <p>
     * F-rest-02: the skip stays best-effort, but it is no longer invisible. Every reference that
     * could not be staged is returned to the caller (and surfaces in the upload response), because
     * a session missing datasets the define declares is otherwise indistinguishable from a complete
     * one — and the check run that follows would report conformance on a partial study.
     * </p>
     */
    private FetchResult expandDefineReferences(String sessionId, URI defineUri,
            Session.FileEntry define)
    {
        ODM odm;
        try
        {
            odm = new DefineXmlParser().parse(define.path().toFile());
        }
        catch (IOException | RuntimeException notADefine)
        {
            LOG.warn("Could not parse {} as Define-XML; staging it as a plain file ({})",
                    define.filename(), notADefine.toString());
            return FetchResult.plain(define);
        }
        DefineSupport support = new DefineSupport(defineUri, odm);
        LinkedHashSet<URI> targets = new LinkedHashSet<>();
        support.getItemGroupDefs().map(support::getUriFor).filter(Objects::nonNull)
                .filter(UrlFileFetcher::isHttp).forEach(targets::add);
        List<String> staged = new ArrayList<>();
        List<SkippedReference> skipped = new ArrayList<>();
        for (URI target : targets)
        {
            String name = deriveFilename(target);
            try
            {
                staged.add(download(sessionId, target, name).filename());
            }
            catch (DuplicateFileException already)
            {
                // Not a gap: the dataset IS in the session, it just did not arrive on this call.
                LOG.debug("Define-referenced dataset {} already staged; skipping", target);
                staged.add(name);
            }
            catch (IOException | RuntimeException e)
            {
                LOG.warn("Skipping define-referenced dataset {} ({})", target, e.toString());
                skipped.add(new SkippedReference(target.toString(), e.toString()));
            }
        }
        return new FetchResult(define, List.copyOf(staged), List.copyOf(skipped));
    }

    /**
     * The outcome of a {@link #fetch} call: the staged file, plus — for a Define-XML — which of the
     * datasets it references reached the session and which did not.
     *
     * @param entry
     *            the file that was staged for the requested URL
     * @param stagedReferences
     *            bare names of the define-referenced datasets now present in the session (freshly
     *            downloaded or already staged); empty when no expansion was performed
     * @param skippedReferences
     *            the define-referenced datasets that could NOT be staged. A non-empty list means
     *            the session holds an incomplete dataset set.
     */
    public record FetchResult(Session.FileEntry entry, List<String> stagedReferences,
            List<SkippedReference> skippedReferences)
    {

        public FetchResult
        {
            stagedReferences = List.copyOf(stagedReferences);
            skippedReferences = List.copyOf(skippedReferences);
        }


        /** A fetch that expanded nothing — a plain file, or a name that is not a Define-XML. */
        static FetchResult plain(Session.FileEntry entry)
        {
            return new FetchResult(entry, List.of(), List.of());
        }
    }


    /**
     * One define-referenced dataset that was not staged, and why.
     *
     * @param url
     *            the absolute URL the define's {@code def:leaf} resolved to
     * @param reason
     *            the failure, as {@code Throwable.toString()}
     */
    public record SkippedReference(String url, String reason)
    {
    }

    private static boolean isDefineXml(String name)
    {
        return name != null && "define.xml".equalsIgnoreCase(name);
    }


    private static boolean isHttp(URI uri)
    {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }


    private static URI parse(String url)
    {
        if (url == null || url.isBlank())
        {
            throw new InvalidUrlException("URL is required");
        }
        URI uri;
        try
        {
            uri = new URI(url.trim());
        }
        catch (URISyntaxException e)
        {
            throw new InvalidUrlException("Malformed URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme))
        {
            throw new InvalidUrlException(
                    "Only http/https URLs are supported (got '" + uri.getScheme() + "')");
        }
        if (uri.getHost() == null)
        {
            throw new InvalidUrlException("URL has no host: " + url);
        }
        return uri;
    }


    /** Last non-empty path segment of the URL, or {@code download} when there is none. */
    private static String deriveFilename(URI uri)
    {
        String path = uri.getPath();
        if (path != null && !path.isEmpty())
        {
            int slash = path.lastIndexOf('/');
            String last = slash >= 0 ? path.substring(slash + 1) : path;
            if (!last.isBlank())
            {
                return last;
            }
        }
        return "download";
    }

    /** A stream that aborts (throws) once more than {@code limit} bytes have been read. */
    private static final class LimitedInputStream extends FilterInputStream
    {

        private final long limit;

        private long count;

        LimitedInputStream(InputStream in, long limit)
        {
            super(in);
            this.limit = limit;
        }


        @Override
        public int read() throws IOException
        {
            int b = super.read();
            if (b != -1)
            {
                tally(1);
            }
            return b;
        }


        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            int n = super.read(b, off, len);
            if (n > 0)
            {
                tally(n);
            }
            return n;
        }


        private void tally(int n)
        {
            count += n;
            if (count > limit)
            {
                throw new DownloadTooLargeException(limit);
            }
        }
    }
}
