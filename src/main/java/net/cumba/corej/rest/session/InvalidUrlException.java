package net.cumba.corej.rest.session;

/**
 * Thrown when a URL-ingest request is invalid: a malformed URL, a disallowed scheme (only
 * {@code http} / {@code https} are accepted), or the remote end failing to serve the resource. Maps
 * to HTTP 400.
 */
public final class InvalidUrlException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public InvalidUrlException(String message)
    {
        super(message);
    }
}
