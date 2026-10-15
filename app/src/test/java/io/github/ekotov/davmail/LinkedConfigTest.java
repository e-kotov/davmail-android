package io.github.ekotov.davmail;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public class LinkedConfigTest {
    private static final String LOCAL = "davmail.mode=EWS\ndavmail.url=https://mail.example.org/EWS/Exchange.asmx\ndavmail.caldavPort=11080\n";
    private static final String LINKED = LOCAL + "davmail.calendarsExclude=@default\n";
    private static final Uri URI_A = Uri.parse("content://test.documents/config-a.properties");
    private static final Uri URI_B = Uri.parse("content://test.documents/config-b.properties");
    private Application app;
    private AtomicReference<String> document;
    private FakeGateway gateway;

    @Before public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("gateway", 0).edit().clear().commit();
        Config.saveLocal(app, LOCAL);
        document = new AtomicReference<>(LINKED);
        Shadows.shadowOf(app.getContentResolver()).registerInputStreamSupplier(URI_A,
                () -> new ByteArrayInputStream(document.get().getBytes(StandardCharsets.UTF_8)));
        gateway = new FakeGateway();
    }

    private void link() throws IOException { Config.link(app, URI_A, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
    private ConfigReloader monitor() { return new ConfigReloader(Config.store(app), gateway); }

    @Test public void linkPersistsReadOnlyPermissionAndKeepsLastGoodCopyUntilApplied() throws Exception {
        link();
        assertEquals(URI_A, Config.linkedUri(app));
        assertEquals(LOCAL, Config.read(app));
        assertEquals(1, app.getContentResolver().getPersistedUriPermissions().size());
        assertTrue(app.getContentResolver().getPersistedUriPermissions().get(0).isReadPermission());
        assertFalse(app.getContentResolver().getPersistedUriPermissions().get(0).isWritePermission());
        ConfigReloader recreated = monitor();
        recreated.start();
        assertEquals(1, gateway.started.size());
        recreated.poll();
        assertEquals(LINKED, Config.read(app));
        assertEquals(LINKED, gateway.started.get(1));
    }

    @Test public void pollsStableContentsWithoutProviderNotifications() throws Exception {
        link();
        ConfigReloader reloader = monitor();
        reloader.start();
        reloader.poll();
        String changed = LINKED + "# edited by chezmoi\n";
        document.set(changed);
        reloader.poll();
        assertEquals(LINKED, Config.read(app));
        reloader.poll();
        assertEquals(changed, Config.read(app));
        int starts = gateway.started.size();
        reloader.poll();
        assertEquals(starts, gateway.started.size());
    }

    @Test public void partialWriteMustSettleAndInvalidFileDoesNotReplaceCache() throws Exception {
        link();
        ConfigReloader reloader = monitor();
        reloader.start();
        document.set("");
        reloader.poll();
        assertEquals(LOCAL, Config.read(app));
        assertTrue(reloader.status().contains("Keeping last working"));
        document.set(LINKED);
        reloader.poll();
        assertEquals(LOCAL, Config.read(app));
        document.set(LINKED + "# still writing\n");
        reloader.poll();
        assertEquals(LOCAL, Config.read(app));
        reloader.poll();
        assertEquals(document.get(), Config.read(app));
    }

    @Test public void restartFailureRestoresRunningConfigAndDoesNotRetryUnchangedBadUpdate() throws Exception {
        link();
        ConfigReloader reloader = monitor();
        reloader.start();
        gateway.failNext = true;
        reloader.poll();
        assertEquals(LOCAL, Config.read(app));
        assertEquals(LOCAL, gateway.started.get(gateway.started.size() - 1));
        assertTrue(reloader.status().contains("rejected"));
        int starts = gateway.started.size();
        reloader.poll();
        assertEquals(starts, gateway.started.size());
        document.set(LINKED + "# repaired\n");
        reloader.poll();
        reloader.poll();
        assertEquals(document.get(), Config.read(app));
    }

    @Test public void revokedOrMissingFileStartsCachedConfigAndRecoversWhenReadable() throws Exception {
        link();
        Shadows.shadowOf(app.getContentResolver()).registerInputStreamSupplier(URI_A,
                () -> { throw new SecurityException("permission revoked"); });
        ConfigReloader reloader = monitor();
        reloader.start();
        assertEquals(LOCAL, gateway.started.get(0));
        assertEquals(LOCAL, Config.read(app));
        assertTrue(reloader.status().contains("Relink"));
        Shadows.shadowOf(app.getContentResolver()).registerInputStreamSupplier(URI_A,
                () -> new ByteArrayInputStream(LINKED.getBytes(StandardCharsets.UTF_8)));
        reloader.poll();
        reloader.poll();
        assertEquals(LINKED, Config.read(app));
    }

    @Test public void unlinkRetainsCopyAndReleasesPermission() throws Exception {
        link();
        ConfigReloader reloader = monitor();
        reloader.start();
        reloader.poll();
        Config.unlink(app);
        document.set(LOCAL + "# external edit after unlink\n");
        reloader.poll();
        assertNull(Config.linkedUri(app));
        assertTrue(app.getContentResolver().getPersistedUriPermissions().isEmpty());
        assertEquals(LINKED, Config.read(app));
    }

    @Test public void importedCopyDoesNotFollowFutureEdits() throws Exception {
        String imported = Config.readDocument(app, URI_A);
        Config.saveLocal(app, imported);
        document.set(LOCAL);
        ConfigReloader reloader = monitor();
        reloader.start();
        reloader.poll();
        assertNull(Config.linkedUri(app));
        assertEquals(LINKED, Config.read(app));
        assertEquals(1, gateway.started.size());
    }

    @Test public void stalePollCannotOverwriteNewlySelectedSourceOrLocalCopy() throws Exception {
        link();
        String stale = Config.readDocument(app, URI_A);
        Config.saveLocal(app, LOCAL);
        assertNull(Config.store(app).applyIfCurrent(URI_A.toString(), stale, gateway));
        assertTrue(gateway.started.isEmpty());
        assertEquals(LOCAL, Config.read(app));
        link();
        Shadows.shadowOf(app.getContentResolver()).registerInputStreamSupplier(URI_B,
                () -> new ByteArrayInputStream(LOCAL.getBytes(StandardCharsets.UTF_8)));
        Config.link(app, URI_B, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(1, app.getContentResolver().getPersistedUriPermissions().size());
        assertNull(Config.store(app).applyIfCurrent(URI_A.toString(), stale, gateway));
    }

    @Test public void malformedOrOversizedLinkLeavesPreviousSourceAndCacheIntact() throws Exception {
        link();
        for (String invalid : new String[]{"", "davmail.caldavPort=0\ndavmail.url=https://example.org\n",
                "davmail.caldavPort=11080\ndavmail.url=not-a-url\n", "#".repeat(262145)}) {
            Shadows.shadowOf(app.getContentResolver()).registerInputStreamSupplier(URI_B,
                    () -> new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8)));
            try {
                Config.link(app, URI_B, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                fail("Invalid config was accepted");
            } catch (IOException expected) { /* Existing selection is preserved. */ }
            assertEquals(URI_A, Config.linkedUri(app));
            assertEquals(LOCAL, Config.read(app));
        }
    }

    @Test public void invalidLocalSaveDoesNotDetachExistingLink() throws Exception {
        link();
        try { Config.saveLocal(app, ""); fail("Empty config accepted"); }
        catch (IOException expected) { /* Validation precedes mode change. */ }
        assertEquals(URI_A, Config.linkedUri(app));
    }

    private static final class FakeGateway implements ConfigReloader.Starter {
        final List<String> started = new ArrayList<>();
        boolean failNext;
        public int start(String text) throws IOException {
            if (failNext) { failNext = false; throw new IOException("simulated bind failure"); }
            started.add(text);
            return 11080;
        }
    }
}
