package net.cumba.corej.rest.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.rest.config.CorejProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

/** Unit tests for {@link SessionRegistry} (no Spring context). */
class SessionRegistryTest
{

    private static InputStream bytes(String s)
    {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }


    private static SessionRegistry newRegistry(CorejProperties props, SessionRunGuard... guards)
        throws IOException
    {
        SessionRegistry registry = new SessionRegistry(props, provider(guards));
        registry.init();
        return registry;
    }


    private static CorejProperties withDir(String dir)
    {
        CorejProperties props = new CorejProperties();
        props.getSessions().setDir(dir);
        return props;
    }


    @Test
    void createAllocatesTempDirAndShutdownRemovesIt() throws IOException
    {
        SessionRegistry registry = newRegistry(new CorejProperties());
        Session session = registry.create();
        Path dir = session.directory();
        assertThat(Files.isDirectory(dir)).isTrue();
        Path base = dir.getParent();

        registry.shutdown();
        assertThat(Files.exists(dir)).isFalse();
        assertThat(Files.exists(base)).isFalse();
    }


    @Test
    void usesConfiguredBaseDirectory(@TempDir Path tmp) throws IOException
    {
        Path base = tmp.resolve("staging");
        SessionRegistry registry = newRegistry(withDir(base.toString()));
        Session session = registry.create();
        assertThat(session.directory().getParent()).isEqualTo(base);
        // A configured (non-temp) base survives shutdown.
        registry.shutdown();
        assertThat(Files.exists(base)).isTrue();
    }


    @Test
    void addFileStoresContentAndReportsSize(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();

        Session.FileEntry entry = registry.addFile(session.id(), "DM.csv", bytes("hello"));

        assertThat(entry.filename()).isEqualTo("DM.csv");
        assertThat(entry.size()).isEqualTo(5L);
        // SHA-256 of "hello", computed once at upload and cached on the entry.
        assertThat(entry.sha256())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(Files.readString(entry.path())).isEqualTo("hello");
        assertThat(session.hasFile("DM.csv")).isTrue();
        assertThat(session.files()).hasSize(1);
    }


    @Test
    void duplicateFilenameIsRejected(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        registry.addFile(session.id(), "DM.csv", bytes("a"));

        assertThatThrownBy(() -> registry.addFile(session.id(), "DM.csv", bytes("b")))
                .isInstanceOf(DuplicateFileException.class);
        // The original content is untouched.
        assertThat(Files.readString(session.directory().resolve("DM.csv"))).isEqualTo("a");
    }


    @Test
    void getUnknownSessionThrows(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        assertThatThrownBy(() -> registry.get("nope")).isInstanceOf(SessionNotFoundException.class);
    }


    @Test
    void allReturnsCreatedSessions(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session a = registry.create();
        Session b = registry.create();
        assertThat(registry.all()).extracting(Session::id).containsExactlyInAnyOrder(a.id(),
                b.id());
    }


    @Test
    void deleteUnknownSessionThrows(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        assertThatThrownBy(() -> registry.delete("nope"))
                .isInstanceOf(SessionNotFoundException.class);
    }


    @Test
    void deleteRemovesSessionAndFiles(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        registry.addFile(session.id(), "DM.csv", bytes("a"));
        Path dir = session.directory();

        registry.delete(session.id());

        assertThat(Files.exists(dir)).isFalse();
        assertThatThrownBy(() -> registry.get(session.id()))
                .isInstanceOf(SessionNotFoundException.class);
    }


    @Test
    void deleteIsVetoedWhileRunsAreInFlight(@TempDir Path tmp) throws IOException
    {
        SessionRunGuard busy = _ -> true;
        SessionRegistry registry = newRegistry(withDir(tmp.toString()), busy);
        Session session = registry.create();

        assertThatThrownBy(() -> registry.delete(session.id()))
                .isInstanceOf(SessionBusyException.class);
        // The session and its directory survive a vetoed delete.
        assertThat(Files.exists(session.directory())).isTrue();
        assertThat(registry.get(session.id())).isSameAs(session);
    }


    @Test
    void deleteProceedsWhenGuardReportsIdle(@TempDir Path tmp) throws IOException
    {
        SessionRunGuard idle = _ -> false;
        SessionRegistry registry = newRegistry(withDir(tmp.toString()), idle);
        Session session = registry.create();

        registry.delete(session.id());
        assertThat(Files.exists(session.directory())).isFalse();
    }


    @Test
    void deleteFileRemovesOneFileAndKeepsTheRest(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        registry.addFile(session.id(), "keep.csv", bytes("a"));
        registry.addFile(session.id(), "gone.csv", bytes("b"));

        registry.deleteFile(session.id(), "gone.csv");

        assertThat(session.files()).extracting(Session.FileEntry::filename)
                .containsExactly("keep.csv");
        assertThat(Files.exists(session.directory().resolve("gone.csv"))).isFalse();
        assertThat(Files.exists(session.directory().resolve("keep.csv"))).isTrue();
        // The manifest is rewritten so the removal survives a reload.
        SessionRegistry second = newRegistry(withDir(tmp.toString()));
        assertThat(second.get(session.id()).files()).extracting(Session.FileEntry::filename)
                .containsExactly("keep.csv");
    }


    @Test
    void deleteUnknownFileThrows(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        assertThatThrownBy(() -> registry.deleteFile(session.id(), "nope.csv"))
                .isInstanceOf(SessionFileNotFoundException.class);
    }


    @Test
    void deleteFileFromUnknownSessionThrows(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        assertThatThrownBy(() -> registry.deleteFile("nope", "DM.csv"))
                .isInstanceOf(SessionNotFoundException.class);
    }


    @Test
    void deleteFileRejectsInvalidName(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        assertThatThrownBy(() -> registry.deleteFile(session.id(), "../evil.csv"))
                .isInstanceOf(InvalidFilenameException.class);
    }


    @Test
    void deleteFileIsVetoedWhileRunsAreInFlight(@TempDir Path tmp) throws IOException
    {
        SessionRunGuard busy = _ -> true;
        SessionRegistry registry = newRegistry(withDir(tmp.toString()), busy);
        Session session = registry.create();
        // Stage a file through a non-vetoed registry so the veto only blocks the delete.
        SessionRegistry plain = newRegistry(withDir(tmp.toString()));
        plain.addFile(session.id(), "DM.csv", bytes("a"));

        assertThatThrownBy(() -> registry.deleteFile(session.id(), "DM.csv"))
                .isInstanceOf(SessionBusyException.class);
        assertThat(Files.exists(session.directory().resolve("DM.csv"))).isTrue();
    }


    @Test
    void deleteAllFilesRemovesEveryFileButKeepsSession(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        registry.addFile(session.id(), "a.csv", bytes("a"));
        registry.addFile(session.id(), "b.csv", bytes("b"));

        int removed = registry.deleteAllFiles(session.id());

        assertThat(removed).isEqualTo(2);
        assertThat(session.files()).isEmpty();
        assertThat(registry.get(session.id())).isSameAs(session);
        assertThat(Files.isDirectory(session.directory())).isTrue();
        assertThat(Files.exists(session.directory().resolve("a.csv"))).isFalse();
        assertThat(Files.exists(session.directory().resolve("b.csv"))).isFalse();
    }


    @Test
    void deleteAllFilesOnEmptySessionReturnsZero(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        assertThat(registry.deleteAllFiles(session.id())).isZero();
    }


    @Test
    void deleteAllFilesIsVetoedWhileRunsAreInFlight(@TempDir Path tmp) throws IOException
    {
        SessionRunGuard busy = _ -> true;
        SessionRegistry registry = newRegistry(withDir(tmp.toString()), busy);
        Session session = registry.create();
        SessionRegistry plain = newRegistry(withDir(tmp.toString()));
        plain.addFile(session.id(), "DM.csv", bytes("a"));

        assertThatThrownBy(() -> registry.deleteAllFiles(session.id()))
                .isInstanceOf(SessionBusyException.class);
        assertThat(Files.exists(session.directory().resolve("DM.csv"))).isTrue();
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "", "   ", "a/b.csv", "a\\b.csv", ".", "..", "sub/DM.csv"
    })
    void invalidFilenamesAreRejected(String name, @TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        assertThatThrownBy(() -> registry.addFile(session.id(), name, bytes("x")))
                .isInstanceOf(InvalidFilenameException.class);
    }


    @Test
    void nullFilenameIsRejected(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        assertThatThrownBy(() -> registry.addFile(session.id(), null, bytes("x")))
                .isInstanceOf(InvalidFilenameException.class);
    }


    @Test
    void sessionsRehydrateFromManifestsOverSameDir(@TempDir Path tmp) throws IOException
    {
        SessionRegistry first = newRegistry(withDir(tmp.toString()));
        Session session = first.create();
        Session.FileEntry entry = first.addFile(session.id(), "DM.csv", bytes("hello"));
        first.shutdown(); // persistent base dir survives

        SessionRegistry second = newRegistry(withDir(tmp.toString()));
        Session reloaded = second.get(session.id());
        assertThat(reloaded.createdAt()).isEqualTo(session.createdAt());
        assertThat(reloaded.files()).hasSize(1);
        Session.FileEntry reEntry = reloaded.files().get(0);
        assertThat(reEntry.filename()).isEqualTo("DM.csv");
        assertThat(reEntry.size()).isEqualTo(5L);
        assertThat(reEntry.sha256()).isEqualTo(entry.sha256());
        assertThat(reEntry.uploadedAt()).isEqualTo(entry.uploadedAt());
        // The manifest sidecar lives next to the staging dir, not inside it (the data library).
        assertThat(Files.exists(tmp.resolve(session.id() + ".session.json"))).isTrue();
        assertThat(Files.exists(session.directory().resolve(session.id() + ".session.json")))
                .isFalse();
    }


    @Test
    void reloadDropsManifestWhenStagingDirMissing(@TempDir Path tmp) throws IOException
    {
        SessionRegistry first = newRegistry(withDir(tmp.toString()));
        Session session = first.create();
        // Remove the staging dir but leave the manifest behind.
        Files.delete(session.directory());

        SessionRegistry second = newRegistry(withDir(tmp.toString()));
        assertThatThrownBy(() -> second.get(session.id()))
                .isInstanceOf(SessionNotFoundException.class);
        assertThat(Files.exists(tmp.resolve(session.id() + ".session.json"))).isFalse();
    }


    @Test
    void reloadDropsVanishedFileButKeepsSession(@TempDir Path tmp) throws IOException
    {
        SessionRegistry first = newRegistry(withDir(tmp.toString()));
        Session session = first.create();
        first.addFile(session.id(), "keep.csv", bytes("a"));
        first.addFile(session.id(), "gone.csv", bytes("b"));
        Files.delete(session.directory().resolve("gone.csv"));

        SessionRegistry second = newRegistry(withDir(tmp.toString()));
        Session reloaded = second.get(session.id());
        assertThat(reloaded.files()).extracting(Session.FileEntry::filename)
                .containsExactly("keep.csv");
    }


    @Test
    void corruptManifestIsSkippedOnReload(@TempDir Path tmp) throws IOException
    {
        SessionRegistry first = newRegistry(withDir(tmp.toString()));
        Session good = first.create();
        Files.writeString(tmp.resolve("bad.session.json"), "{ not json", StandardCharsets.UTF_8);

        SessionRegistry second = newRegistry(withDir(tmp.toString()));
        assertThat(second.get(good.id())).isNotNull();
        assertThatThrownBy(() -> second.get("bad")).isInstanceOf(SessionNotFoundException.class);
    }


    @Test
    void rehydrationCanBeDisabled(@TempDir Path tmp) throws IOException
    {
        SessionRegistry first = newRegistry(withDir(tmp.toString()));
        Session session = first.create();

        CorejProperties props = withDir(tmp.toString());
        props.getPersistence().setRehydrateOnStartup(false);
        SessionRegistry second = newRegistry(props);
        assertThatThrownBy(() -> second.get(session.id()))
                .isInstanceOf(SessionNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // Session naming
    // ------------------------------------------------------------------


    @Test
    void createStoresTrimmedName(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create("  My study  ");
        assertThat(session.name()).isEqualTo("My study");
        assertThat(registry.findName(session.id())).isEqualTo("My study");
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "", "   ", "\t\n"
    })
    void createWithBlankNameIsUnnamed(String name, @TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        assertThat(registry.create(name).name()).isNull();
    }


    @Test
    void createWithNullNameIsUnnamed(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        assertThat(registry.create((String) null).name()).isNull();
        // The no-arg overload is equivalent.
        assertThat(registry.create().name()).isNull();
    }


    @Test
    void createRejectsTooLongName(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        String tooLong = "x".repeat(SessionRegistry.MAX_NAME_LENGTH + 1);
        assertThatThrownBy(() -> registry.create(tooLong))
                .isInstanceOf(InvalidSessionNameException.class);
    }


    @Test
    void createRejectsControlCharacterName(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        assertThatThrownBy(() -> registry.create("bad\u0001name"))
                .isInstanceOf(InvalidSessionNameException.class);
    }


    @Test
    void renameSetsChangesAndClearsTheName(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        assertThat(session.name()).isNull();

        assertThat(registry.rename(session.id(), "First").name()).isEqualTo("First");
        assertThat(registry.rename(session.id(), "Second").name()).isEqualTo("Second");
        // Blank clears the name back to unnamed.
        assertThat(registry.rename(session.id(), "   ").name()).isNull();
        assertThat(registry.rename(session.id(), "Third").name()).isEqualTo("Third");
        assertThat(registry.rename(session.id(), null).name()).isNull();
    }


    @Test
    void renamePersistsAcrossReload(@TempDir Path tmp) throws IOException
    {
        SessionRegistry first = newRegistry(withDir(tmp.toString()));
        Session session = first.create("Original");
        first.rename(session.id(), "Renamed");
        first.shutdown();

        SessionRegistry second = newRegistry(withDir(tmp.toString()));
        assertThat(second.get(session.id()).name()).isEqualTo("Renamed");
    }


    @Test
    void renameUnknownSessionThrows(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        assertThatThrownBy(() -> registry.rename("nope", "x"))
                .isInstanceOf(SessionNotFoundException.class);
    }


    @Test
    void renameRejectsInvalidName(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        Session session = registry.create();
        assertThatThrownBy(() -> registry.rename(session.id(),
                "x".repeat(SessionRegistry.MAX_NAME_LENGTH + 1)))
                        .isInstanceOf(InvalidSessionNameException.class);
    }


    @Test
    void nameRoundTripsAcrossReload(@TempDir Path tmp) throws IOException
    {
        SessionRegistry first = newRegistry(withDir(tmp.toString()));
        Session named = first.create("Named study");
        Session unnamed = first.create();
        first.shutdown();

        SessionRegistry second = newRegistry(withDir(tmp.toString()));
        assertThat(second.get(named.id()).name()).isEqualTo("Named study");
        assertThat(second.get(unnamed.id()).name()).isNull();
    }


    @Test
    void legacyManifestWithoutNameRehydratesAsUnnamed(@TempDir Path tmp) throws IOException
    {
        SessionRegistry first = newRegistry(withDir(tmp.toString()));
        Session session = first.create("Will be stripped");
        first.shutdown();

        // Simulate a manifest written before the naming feature: no `name` field.
        Path manifest = tmp.resolve(session.id() + ".session.json");
        String legacy = "{\"id\":\"" + session.id() + "\",\"createdAt\":\""
                + session.createdAt().toString() + "\",\"files\":[]}";
        Files.writeString(manifest, legacy, StandardCharsets.UTF_8);

        SessionRegistry second = newRegistry(withDir(tmp.toString()));
        assertThat(second.get(session.id()).name()).isNull();
    }


    @Test
    void findNameReturnsNullForUnknownSession(@TempDir Path tmp) throws IOException
    {
        SessionRegistry registry = newRegistry(withDir(tmp.toString()));
        assertThat(registry.findName("nope")).isNull();
    }


    /** Minimal {@link ObjectProvider} over a fixed set of guards (Spring not on the test path). */
    private static ObjectProvider<SessionRunGuard> provider(SessionRunGuard... guards)
    {
        List<SessionRunGuard> list = List.of(guards);
        return new ObjectProvider<>()
        {

            @Override
            public SessionRunGuard getObject()
            {
                throw new UnsupportedOperationException();
            }


            @Override
            public SessionRunGuard getObject(Object... args)
            {
                throw new UnsupportedOperationException();
            }


            @Override
            public SessionRunGuard getIfAvailable()
            {
                return list.isEmpty() ? null : list.get(0);
            }


            @Override
            public SessionRunGuard getIfUnique()
            {
                return list.size() == 1 ? list.get(0) : null;
            }


            @Override
            public Iterator<SessionRunGuard> iterator()
            {
                return list.iterator();
            }


            @Override
            public Stream<SessionRunGuard> stream()
            {
                return list.stream();
            }
        };
    }
}
