package net.cumba.corej.rest.web;

import java.io.UncheckedIOException;
import net.cumba.corej.rest.report.V2ReportFormatException;
import net.cumba.corej.rest.run.BadRunRequestException;
import net.cumba.corej.rest.run.RunConflictException;
import net.cumba.corej.rest.run.RunNotFoundException;
import net.cumba.corej.rest.session.DownloadTooLargeException;
import net.cumba.corej.rest.session.DuplicateFileException;
import net.cumba.corej.rest.session.InvalidFilenameException;
import net.cumba.corej.rest.session.InvalidSessionNameException;
import net.cumba.corej.rest.session.InvalidUrlException;
import net.cumba.corej.rest.session.SessionBusyException;
import net.cumba.corej.rest.session.SessionFileNotFoundException;
import net.cumba.corej.rest.session.SessionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the service's domain exceptions to RFC-7807 problem responses across the session and check
 * endpoints.
 */
@RestControllerAdvice
public class RestExceptionHandler
{

    @ExceptionHandler(
    {
            SessionNotFoundException.class, SessionFileNotFoundException.class,
            RunNotFoundException.class
    })
    public ProblemDetail handleNotFound(RuntimeException ex)
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }


    @ExceptionHandler(
    {
            InvalidFilenameException.class, InvalidSessionNameException.class,
            BadRunRequestException.class, InvalidUrlException.class
    })
    public ProblemDetail handleBadRequest(RuntimeException ex)
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }


    @ExceptionHandler(DownloadTooLargeException.class)
    public ProblemDetail handlePayloadTooLarge(DownloadTooLargeException ex)
    {
        // 413; HttpStatus.PAYLOAD_TOO_LARGE is deprecated in favour of CONTENT_TOO_LARGE.
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
    }


    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex)
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Malformed or unreadable request body");
    }


    @ExceptionHandler(UncheckedIOException.class)
    public ProblemDetail handleStorageFailure(UncheckedIOException ex)
    {
        // A server-side storage fault: a report, v2 report, XLSX, execution log, rule-definition
        // or session-directory operation that could not be completed.
        //
        // F-rest-05: report the ACTUAL cause. Every producer in ReportStore throws with a message
        // naming both the artifact and the run id ("Corrupt report file for run <id>"), and this
        // handler used to replace all of them with the constant "Failed to read stored findings" —
        // so a corrupt *report* was reported as a *findings* failure and the run id, the one piece
        // of information that makes the 500 actionable, was dropped. These messages are written by
        // this service, carry no user input beyond the run id, and are the diagnosis.
        String detail = ex.getMessage();
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                detail == null || detail.isBlank() ? "Failed to read stored report data" : detail);
    }


    @ExceptionHandler(V2ReportFormatException.class)
    public ProblemDetail handleUnrecognisedReportShape(V2ReportFormatException ex)
    {
        // The stored v2 report does not have the shape the findings projection reads. Answering
        // 500 rather than an empty page is the point: a lenient fallback would serve silently
        // empty columns. The exception message (with the offending JSON path) reaches the log.
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Stored findings document has an unrecognised shape");
    }


    @ExceptionHandler(
    {
            DuplicateFileException.class, SessionBusyException.class, RunConflictException.class
    })
    public ProblemDetail handleConflict(RuntimeException ex)
    {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
