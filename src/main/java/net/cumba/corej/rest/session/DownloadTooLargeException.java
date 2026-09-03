package net.cumba.corej.rest.session;

/**
 * Thrown when a URL-ingest download exceeds the configured maximum size
 * ({@code corej.sessions.max-download-bytes}). The partial file is removed. Maps to HTTP 413.
 */
public final class DownloadTooLargeException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public DownloadTooLargeException(long maxBytes)
    {
        super("Downloaded resource exceeds the maximum allowed size of " + maxBytes + " bytes");
    }
}
