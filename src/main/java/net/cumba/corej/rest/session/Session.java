package net.cumba.corej.rest.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * One upload session: an id, a private staging directory, and the set of files uploaded into it.
 * Files are keyed by their (bare) name; a name may be added at most once. Thread-safe — uploads
 * across concurrent requests are serialised per filename via {@link ConcurrentMap#putIfAbsent}.
 */
public final class Session
{

    private final String id;

    private final Path directory;

    private final Instant createdAt;

    /** Optional human-readable display name; {@code null} when the session is unnamed. */
    private volatile @Nullable String name;

    private final ConcurrentMap<String, FileEntry> files = new ConcurrentHashMap<>();

    Session(String id, Path directory, @Nullable String name)
    {
        this(id, directory, name, Instant.now());
    }


    private Session(String id, Path directory, @Nullable String name, Instant createdAt)
    {
        this.id = id;
        this.directory = directory;
        this.name = name;
        this.createdAt = createdAt;
    }


    /**
     * Rebuild a session from its persisted manifest: its original id, staging directory, name and
     * creation time, pre-populated with the given (already-known) file entries — no content is read
     * or re-hashed.
     */
    static Session restore(String id, Path directory, @Nullable String name, Instant createdAt,
            List<FileEntry> entries)
    {
        Session session = new Session(id, directory, name, createdAt);
        for (FileEntry entry : entries)
        {
            session.files.put(entry.filename(), entry);
        }
        return session;
    }


    public String id()
    {
        return id;
    }


    /** The session's private staging directory; the data library a run reads from. */
    public Path directory()
    {
        return directory;
    }


    public Instant createdAt()
    {
        return createdAt;
    }


    /** The session's optional display name; {@code null} when unnamed. */
    public @Nullable String name()
    {
        return name;
    }


    /** Set or clear (when {@code null}) the session's display name. */
    void setName(@Nullable String name)
    {
        this.name = name;
    }


    /** Snapshot of the files currently in this session. */
    public List<FileEntry> files()
    {
        return List.copyOf(files.values());
    }


    /**
     * Stream {@code content} into the session under {@code filename}, which must already be a
     * validated bare name. Reserves the name first so two concurrent uploads of the same name can
     * never both win.
     *
     * @throws DuplicateFileException
     *             if the name is already present
     * @throws IOException
     *             if writing the file fails
     */
    FileEntry addFile(String filename, InputStream content) throws IOException
    {
        Path target = directory.resolve(filename);
        FileEntry placeholder = new FileEntry(filename, target, -1L, Instant.now(), null);
        if (files.putIfAbsent(filename, placeholder) != null)
        {
            throw new DuplicateFileException(id, filename);
        }
        try
        {
            // Compute the SHA-256 once, as the bytes stream to disk, and cache it on the entry —
            // so the per-run execution log can report it without re-reading the (possibly large)
            // file on every run.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (DigestInputStream digesting = new DigestInputStream(content, digest))
            {
                size = Files.copy(digesting, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            FileEntry stored = new FileEntry(filename, target, size, placeholder.uploadedAt(),
                    sha256);
            files.put(filename, stored);
            return stored;
        }
        catch (NoSuchAlgorithmException e)
        {
            files.remove(filename, placeholder);
            throw new IOException("SHA-256 algorithm not available", e);
        }
        catch (IOException | RuntimeException e)
        {
            files.remove(filename, placeholder);
            // Remove any partially-written file so a mid-copy failure (e.g. a URL
            // download aborted at the size cap) does not leave an orphan on disk.
            try
            {
                Files.deleteIfExists(target);
            }
            catch (IOException cleanup)
            {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }


    /** Whether a file with this exact name is already present. */
    public boolean hasFile(String filename)
    {
        return files.containsKey(filename);
    }


    /**
     * Remove the file with this (bare) name from the session and delete it from disk.
     *
     * @return {@code true} if the file was present and removed; {@code false} if no such file
     *         existed.
     * @throws IOException
     *             if deleting the file from disk fails
     */
    boolean removeFile(String filename) throws IOException
    {
        FileEntry removed = files.remove(filename);
        if (removed == null)
        {
            return false;
        }
        Files.deleteIfExists(removed.path());
        return true;
    }


    /**
     * Remove every file from the session and delete them from disk.
     *
     * @return the number of files removed.
     * @throws IOException
     *             if deleting any file from disk fails
     */
    int clearFiles() throws IOException
    {
        int count = 0;
        for (String name : List.copyOf(files.keySet()))
        {
            if (removeFile(name))
            {
                count++;
            }
        }
        return count;
    }


    Map<String, FileEntry> fileMap()
    {
        return files;
    }

    /**
     * A single uploaded file's metadata. {@code size} is {@code -1} and {@code sha256} is
     * {@code null} only for a transient reservation (before the content has been written).
     */
    public record FileEntry(String filename, Path path, long size, Instant uploadedAt,
            @Nullable String sha256)
    {
    }
}
