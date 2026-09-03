package net.cumba.corej.rest.run;

/**
 * Thrown when a check-run request is invalid: a missing required field, or a referenced file that
 * is not a bare name present in the session. Maps to HTTP 400.
 */
public final class BadRunRequestException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public BadRunRequestException(String message)
    {
        super(message);
    }
}
