package net.cumba.corej.rest.run;

/** Thrown when a check-run id does not resolve to a known run. Maps to HTTP 404. */
public final class RunNotFoundException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public RunNotFoundException(String runId)
    {
        super("No such check run: " + runId);
    }
}
