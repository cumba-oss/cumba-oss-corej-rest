package net.cumba.corej.rest.session;

/**
 * Thrown when an upload's {@code filename} is missing or carries a path component. The API takes a
 * bare filename only; any separator or relative segment is rejected. Maps to HTTP 400.
 */
public final class InvalidFilenameException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public InvalidFilenameException(String filename, String reason)
    {
        super("Invalid filename '" + filename + "': " + reason);
    }
}
