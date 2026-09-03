package net.cumba.corej.rest.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import net.cumba.cdisc.define.DefineXmlConverter;
import net.cumba.corej.rest.session.Session;
import net.cumba.corej.rest.session.SessionRegistry;
import net.cumba.corej.rest.session.UrlFileFetcher;
import net.cumba.corej.rest.web.dto.CreateSessionRequest;
import net.cumba.corej.rest.web.dto.CreateSessionResponse;
import net.cumba.corej.rest.web.dto.DefineVersionResponse;
import net.cumba.corej.rest.web.dto.FileUploadResponse;
import net.cumba.corej.rest.web.dto.RenameSessionRequest;
import net.cumba.corej.rest.web.dto.SessionSummary;
import net.cumba.corej.rest.web.dto.UrlUploadRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

/**
 * Session lifecycle endpoints: create a session, stage files into it, and delete it. A session's
 * files can drive several check runs (created via the check endpoints); deletion is rejected while
 * any of those runs are in flight.
 */
@RestController
@RequestMapping("/api/sessions")
@Tag(name = "sessions", description = "Upload sessions and their staged files")
public class SessionController
{

    private final SessionRegistry registry;

    private final UrlFileFetcher urlFileFetcher;

    public SessionController(SessionRegistry registry, UrlFileFetcher urlFileFetcher)
    {
        this.registry = registry;
        this.urlFileFetcher = urlFileFetcher;
    }


    @PostMapping
    @Operation(summary = "Create a new upload session",
            description = "Optionally names the session via a JSON body { name }; an empty body "
                    + "creates an unnamed session.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "201", description = "Session created"), @ApiResponse(
                    responseCode = "400", description = "Invalid session name", content = @Content)
    })
    public ResponseEntity<CreateSessionResponse> create(
            @RequestBody(required = false) @Nullable CreateSessionRequest request)
    {
        Session session = registry.create(request == null ? null : request.name());
        return ResponseEntity.created(URI.create("/api/sessions/" + session.id()))
                .body(new CreateSessionResponse(session.id(), session.name()));
    }


    @GetMapping
    @Operation(summary = "List upload sessions",
            description = "Returns a summary of every live session, most recently created first.")
    public List<SessionSummary> list()
    {
        return registry.all().stream().sorted(
                Comparator.comparing(Session::createdAt).reversed().thenComparing(Session::id))
                .map(SessionSummary::from).toList();
    }


    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rename a session",
            description = "Sets or clears (blank/null) the session's display name. Not gated by "
                    + "in-flight runs — it touches only metadata.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200", description = "Updated session summary"),
            @ApiResponse(responseCode = "400", description = "Invalid session name",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content)
    })
    public SessionSummary rename(@PathVariable String id, @RequestBody RenameSessionRequest request)
    {
        return SessionSummary.from(registry.rename(id, request.name()));
    }


    @PostMapping(path = "/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Stage a file into a session",
            description = "Uploads one file under the given bare filename. The filename must not carry a path"
                    + " component, and a name may be uploaded at most once per session.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "201", description = "File staged"),
            @ApiResponse(responseCode = "400", description = "Filename missing or carries a path",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content),
            @ApiResponse(responseCode = "409", description = "Filename already present",
                    content = @Content)
    })
    public ResponseEntity<FileUploadResponse> upload(@PathVariable String id,
            @RequestParam("filename") String filename, @RequestPart("file") MultipartFile file)
        throws IOException
    {
        Session.FileEntry entry;
        try (var in = file.getInputStream())
        {
            entry = registry.addFile(id, filename, in);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FileUploadResponse(id, entry.filename(), entry.size()));
    }


    @PostMapping(path = "/{id}/files/from-url", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Stage a file into a session by URL",
            description = "The server downloads the http/https URL into the session under the given "
                    + "bare filename (derived from the URL path when omitted). Bounded by "
                    + "corej.sessions.max-download-bytes and corej.sessions.download-timeout; the "
                    + "JVM's global proxy system properties apply. A name may be staged at most once "
                    + "per session.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "201", description = "File staged"),
            @ApiResponse(responseCode = "400",
                    description = "Malformed/disallowed URL or invalid filename",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content),
            @ApiResponse(responseCode = "409", description = "Filename already present",
                    content = @Content),
            @ApiResponse(responseCode = "413", description = "Download exceeds the size cap",
                    content = @Content)
    })
    public ResponseEntity<FileUploadResponse> uploadFromUrl(@PathVariable String id,
            @RequestBody UrlUploadRequest request)
        throws IOException
    {
        Session.FileEntry entry = urlFileFetcher.fetch(id, request.url(), request.filename());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FileUploadResponse(id, entry.filename(), entry.size()));
    }


    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a session and its staged files",
            description = "Rejected (409) while any of the session's check runs are PENDING or RUNNING.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "204", description = "Session deleted"),
            @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content),
            @ApiResponse(responseCode = "409", description = "Session has in-flight runs",
                    content = @Content)
    })
    public ResponseEntity<Void> delete(@PathVariable String id)
    {
        registry.delete(id);
        return ResponseEntity.noContent().build();
    }


    @DeleteMapping("/{id}/files")
    @Operation(summary = "Delete all staged files from a session",
            description = "Removes every staged file but keeps the (empty) session. Rejected (409) "
                    + "while any of the session's check runs are PENDING or RUNNING.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "204", description = "All files deleted"),
            @ApiResponse(responseCode = "404", description = "Unknown session", content = @Content),
            @ApiResponse(responseCode = "409", description = "Session has in-flight runs",
                    content = @Content)
    })
    public ResponseEntity<Void> deleteAllFiles(@PathVariable String id) throws IOException
    {
        registry.deleteAllFiles(id);
        return ResponseEntity.noContent().build();
    }


    @DeleteMapping("/{id}/files/{filename}")
    @Operation(summary = "Delete one staged file from a session",
            description = "Removes the file with the given bare name. Rejected (409) while any of "
                    + "the session's check runs are PENDING or RUNNING.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "204", description = "File deleted"),
            @ApiResponse(responseCode = "400", description = "Filename carries a path",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Unknown session or file",
                    content = @Content),
            @ApiResponse(responseCode = "409", description = "Session has in-flight runs",
                    content = @Content)
    })
    public ResponseEntity<Void> deleteFile(@PathVariable String id, @PathVariable String filename)
        throws IOException
    {
        registry.deleteFile(id, filename);
        return ResponseEntity.noContent().build();
    }


    @GetMapping("/{id}/files/{filename}/define-version")
    @Operation(summary = "Detect the Define-XML version of a staged file",
            description = "Parses the named file and reports the detected Define-XML version "
                    + "(1.0 / 2.0 / 2.1) from its content. Both response fields are null when the "
                    + "file is not a recognisable Define-XML document. The web UI calls this when a "
                    + "define.xml is selected to pre-fill the define-version field.")
    @ApiResponses(
    {
            @ApiResponse(responseCode = "200",
                    description = "Detected version (fields null if undeterminable)"),
            @ApiResponse(responseCode = "400", description = "Filename carries a path",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Unknown session or file",
                    content = @Content)
    })
    public DefineVersionResponse defineVersion(@PathVariable String id,
            @PathVariable String filename)
    {
        Path path = registry.filePath(id, filename);
        DefineXmlConverter.Version version = detectVersion(path);
        return new DefineVersionResponse(version == null ? null : version.label(),
                version == null ? null : version.defineVersion());
    }


    /** Detect the version of a file, treating a non-XML / unparsable file as undeterminable. */
    private static DefineXmlConverter.@Nullable Version detectVersion(Path path)
    {
        try
        {
            return DefineXmlConverter.forFile(path).detectInputVersion();
        }
        catch (IOException | SAXException | ParserConfigurationException _)
        {
            return null;
        }
    }
}
