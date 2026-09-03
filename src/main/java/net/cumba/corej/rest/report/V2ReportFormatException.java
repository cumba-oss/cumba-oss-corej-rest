package net.cumba.corej.rest.report;

/**
 * The stored v2 combined-finding report does not have a shape {@link V2Findings} recognises.
 *
 * <p>
 * Raised eagerly and unconditionally: a lenient parse would turn a silently-absent field into a
 * silently-empty column on the findings API, and nothing would go red. The message names the exact
 * JSON path so the mismatch between the writer
 * ({@code net.cumba.corej.core.report.json.JsonReportWriter}) and this reader is diagnosable from a
 * log line.
 * </p>
 */
public class V2ReportFormatException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public V2ReportFormatException(String aMessage)
    {
        super(aMessage);
    }
}
