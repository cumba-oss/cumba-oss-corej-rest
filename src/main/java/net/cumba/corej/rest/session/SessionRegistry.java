package net.cumba.corej.rest.session;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.cumba.corej.rest.config.CorejProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-memory registry of upload sessions. Each session owns a private staging directory under a
 * configurable base ({@code corej.sessions.dir}, or a fresh OS temp dir when unset). Deletion is
 * vetoed while the session has in-flight runs, consulted through an optional
 * {@link SessionRunGuard} (absent in Phase 2, supplied by the run registry later).
 */
@Component
public class SessionRegistry
{

    private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

    /** Maximum length of a session display name. */
    static final int MAX_NAME_LENGTH = 200;

    private final CorejProperties properties;

    private final ObjectProvider<SessionRunGuard> runGuard;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    private Path baseDir;

    private boolean baseDirIsTemp;

    public SessionRegistry(CorejProperties properties, ObjectProvider<SessionRunGuard> runGuard)
    {
        this.properties = properties;
        this.runGuard = runGuard;
    }


    @PostConstruct
    void init() throws IOException
    {
        String configured = properties.getSessions().getDir();
        if (configured != null && !configured.isBlank())
        {
            baseDir = Path.of(configured);
            Files.createDirectories(baseDir);
            baseDirIsTemp = false;
        }
        else
        {
            baseDir = Files.createTempDirectory("corej-sessions");
            baseDirIsTemp = true;
        }
        LOG.info("Session staging base directory: {}", baseDir);
        // A temp base dir is always freshly created, so there is nothing to rehydrate.
        if (!baseDirIsTemp && properties.getPersistence().isRehydrateOnStartup())
        {
            reload();
        }
    }


    /**
     * Rebuild sessions from their sibling {@code *.session.json} manifests under {@link #baseDir}.
     */
    private void reload()
    {
        try (var entries = Files.list(baseDir))
        {
            entries.filter(SessionRegistry::isManifest).forEach(this::reloadOne);
        }
        catch (IOException e)
        {
            LOG.warn("Failed to scan {} for session manifests", baseDir, e);
        }
        if (!sessions.isEmpty())
        {
            LOG.info("Rehydrated {} session(s) from {}", sessions.size(), baseDir);
        }
    }


    private static boolean isManifest(Path path)
    {
        Path name = path.getFileName();
        return name != null && name.toString().endsWith(".session.json");
    }


    private void reloadOne(Path manifestPath)
    {
        try
        {
            SessionManifest manifest = mapper.readValue(
                    Files.readString(manifestPath, StandardCharsets.UTF_8), SessionManifest.class);
            Path dir = baseDir.resolve(manifest.id());
            if (!Files.isDirectory(dir))
            {
                LOG.warn("Dropping session manifest {}: staging directory is missing",
                        manifest.id());
                Files.deleteIfExists(manifestPath);
                return;
            }
            List<Session.FileEntry> entries = new ArrayList<>(manifest.files().size());
            for (SessionManifest.FileRecord fr : manifest.files())
            {
                Path filePath = dir.resolve(fr.filename());
                if (!Files.isRegularFile(filePath))
                {
                    LOG.warn("Session {}: staged file {} missing on disk; dropping from manifest",
                            manifest.id(), fr.filename());
                    continue;
                }
                entries.add(new Session.FileEntry(fr.filename(), filePath, fr.size(),
                        Instant.parse(fr.uploadedAt()), fr.sha256()));
            }
            Session session = Session.restore(manifest.id(), dir, manifest.name(),
                    Instant.parse(manifest.createdAt()), entries);
            sessions.put(manifest.id(), session);
            // If files vanished under us, rewrite the manifest so it stays consistent with disk.
            if (entries.size() != manifest.files().size())
            {
                writeManifest(session);
            }
        }
        catch (IOException | RuntimeException e)
        {
            LOG.warn("Failed to reload session manifest {}", manifestPath, e);
        }
    }


    private Path manifestFor(String sessionId)
    {
        return baseDir.resolve(sessionId + ".session.json");
    }


    /**
     * Write {@code content} to {@code target} via a sibling {@code .tmp} file and an atomic move,
     * so a crash mid-write never leaves a half-written manifest (which would drop the session on
     * reload). The {@code .tmp} sibling is ignored by the manifest scan.
     */
    private static void writeAtomically(Path target, String content) throws IOException
    {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try
        {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }


    /** Persist (or refresh) a session's manifest. Best-effort: failures are logged, not thrown. */
    private void writeManifest(Session session)
    {
        List<SessionManifest.FileRecord> files = session.files().stream()
                .map(f -> new SessionManifest.FileRecord(f.filename(), f.size(),
                        f.uploadedAt().toString(), f.sha256()))
                .toList();
        try
        {
            String json = mapper.writeValueAsString(new SessionManifest(session.id(),
                    session.name(), session.createdAt().toString(), files));
            writeAtomically(manifestFor(session.id()), json);
        }
        catch (IOException | RuntimeException e)
        {
            LOG.warn("Failed to persist session manifest for {}", session.id(), e);
        }
    }


    /** Create a new unnamed session with a fresh id and an empty staging directory. */
    public Session create()
    {
        return create(null);
    }


    /**
     * Create a new session with a fresh id, an empty staging directory and an optional display name
     * (blank/null leaves it unnamed).
     *
     * @throws InvalidSessionNameException
     *             the name is too long or carries control characters
     */
    public Session create(@Nullable String name)
    {
        String normalized = normalizeName(name);
        String id = UUID.randomUUID().toString();
        try
        {
            Path dir = Files.createDirectories(baseDir.resolve(id));
            Session session = new Session(id, dir, normalized);
            sessions.put(id, session);
            writeManifest(session);
            return session;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to create session directory for " + id, e);
        }
    }


    /**
     * Set or clear a session's display name (blank/null clears it) and refresh its manifest.
     * Touches only metadata, so it is not gated by in-flight runs.
     *
     * @throws SessionNotFoundException
     *             unknown session
     * @throws InvalidSessionNameException
     *             the name is too long or carries control characters
     */
    public Session rename(String sessionId, @Nullable String name)
    {
        Session session = get(sessionId);
        session.setName(normalizeName(name));
        writeManifest(session);
        return session;
    }


    /** Current name of a session, or {@code null} if it is unnamed or unknown (non-throwing). */
    public @Nullable String findName(String sessionId)
    {
        Session session = sessions.get(sessionId);
        return session == null ? null : session.name();
    }


    /**
     * Normalise a session name: a blank/null name becomes {@code null} (unnamed); otherwise it is
     * trimmed and validated for length and control characters.
     */
    private static @Nullable String normalizeName(@Nullable String name)
    {
        if (name == null || name.isBlank())
        {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH)
        {
            throw new InvalidSessionNameException(
                    "must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (trimmed.chars().anyMatch(c -> c < 0x20 || c == 0x7f))
        {
            throw new InvalidSessionNameException("must not contain control characters");
        }
        return trimmed;
    }


    /** Snapshot of all live sessions. */
    public Collection<Session> all()
    {
        return sessions.values();
    }


    /**
     * Returns the live session with the given id.
     *
     * @throws SessionNotFoundException
     *             if the id is unknown.
     */
    public Session get(String sessionId)
    {
        Session session = sessions.get(sessionId);
        if (session == null)
        {
            throw new SessionNotFoundException(sessionId);
        }
        return session;
    }


    /**
     * Add an uploaded file to a session under a bare {@code filename}.
     *
     * @throws SessionNotFoundException
     *             unknown session
     * @throws InvalidFilenameException
     *             blank name or one carrying a path component
     * @throws DuplicateFileException
     *             the name already exists in the session
     */
    public Session.FileEntry addFile(String sessionId, String filename, InputStream content)
        throws IOException
    {
        Session session = get(sessionId);
        String bare = validateFilename(filename);
        Session.FileEntry entry = session.addFile(bare, content);
        // Refresh the manifest only after the file is fully written, so a DuplicateFileException or
        // a failed copy never rewrites it.
        writeManifest(session);
        return entry;
    }


    /**
     * Delete a session and its staging directory.
     *
     * @throws SessionNotFoundException
     *             unknown session
     * @throws SessionBusyException
     *             the session still has {@code PENDING}/{@code RUNNING} runs
     */
    public void delete(String sessionId)
    {
        Session session = get(sessionId);
        ensureNoInFlightRuns(sessionId);
        sessions.remove(sessionId);
        deleteRecursively(session.directory());
        try
        {
            Files.deleteIfExists(manifestFor(sessionId));
        }
        catch (IOException e)
        {
            LOG.warn("Failed to delete session manifest for {}", sessionId, e);
        }
    }


    /**
     * Delete one staged file from a session and refresh its manifest.
     *
     * @throws SessionNotFoundException
     *             unknown session
     * @throws SessionBusyException
     *             the session still has {@code PENDING}/{@code RUNNING} runs
     * @throws InvalidFilenameException
     *             blank name or one carrying a path component
     * @throws SessionFileNotFoundException
     *             no file with that name exists in the session
     */
    public void deleteFile(String sessionId, String filename) throws IOException
    {
        Session session = get(sessionId);
        ensureNoInFlightRuns(sessionId);
        String bare = validateFilename(filename);
        if (!session.removeFile(bare))
        {
            throw new SessionFileNotFoundException(sessionId, bare);
        }
        writeManifest(session);
    }


    /**
     * Resolve the on-disk path of a staged file (read-only; does not touch run state).
     *
     * @throws SessionNotFoundException
     *             unknown session
     * @throws InvalidFilenameException
     *             blank name or one carrying a path component
     * @throws SessionFileNotFoundException
     *             no file with that name exists in the session
     */
    public Path filePath(String sessionId, String filename)
    {
        Session session = get(sessionId);
        String bare = validateFilename(filename);
        return session.files().stream().filter(f -> f.filename().equals(bare))
                .map(Session.FileEntry::path).findFirst()
                .orElseThrow(() -> new SessionFileNotFoundException(sessionId, bare));
    }


    /**
     * Delete every staged file from a session (the session itself remains) and refresh its
     * manifest.
     *
     * @return the number of files removed.
     * @throws SessionNotFoundException
     *             unknown session
     * @throws SessionBusyException
     *             the session still has {@code PENDING}/{@code RUNNING} runs
     */
    public int deleteAllFiles(String sessionId) throws IOException
    {
        Session session = get(sessionId);
        ensureNoInFlightRuns(sessionId);
        int removed = session.clearFiles();
        writeManifest(session);
        return removed;
    }


    private void ensureNoInFlightRuns(String sessionId)
    {
        if (runGuard.stream().anyMatch(g -> g.hasInFlightRuns(sessionId)))
        {
            throw new SessionBusyException(sessionId);
        }
    }


    private static String validateFilename(String filename)
    {
        if (filename == null || filename.isBlank())
        {
            throw new InvalidFilenameException(String.valueOf(filename), "must not be blank");
        }
        if (filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0)
        {
            throw new InvalidFilenameException(filename, "must not contain a path separator");
        }
        if (filename.indexOf('\0') >= 0)
        {
            throw new InvalidFilenameException(filename, "must not contain a NUL character");
        }
        if (".".equals(filename) || "..".equals(filename))
        {
            throw new InvalidFilenameException(filename, "must not be a relative path segment");
        }
        // Belt-and-suspenders: the name must resolve to a single path element.
        Path asPath = Path.of(filename);
        Path fileName = asPath.getFileName();
        if (asPath.getNameCount() != 1 || fileName == null || !fileName.toString().equals(filename))
        {
            throw new InvalidFilenameException(filename, "must be a bare file name");
        }
        return filename;
    }


    private static void deleteRecursively(Path dir)
    {
        if (!Files.exists(dir))
        {
            return;
        }
        try (var paths = Files.walk(dir))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(p ->
            {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException e)
                {
                    LOG.warn("Failed to delete {}", p, e);
                }
            });
        }
        catch (IOException e)
        {
            LOG.warn("Failed to walk {} for deletion", dir, e);
        }
    }


    @PreDestroy
    void shutdown()
    {
        // Persistent staging dirs (and their manifests) are intentionally left in place so sessions
        // survive a restart; only a temp base dir — always freshly allocated at startup — is wiped.
        sessions.clear();
        if (baseDirIsTemp && baseDir != null)
        {
            deleteRecursively(baseDir);
        }
    }
}
