package io.github.ekotov.davmail;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** App-private DavMail configuration and run state. */
final class Config {
    private static final String PREFS = "gateway";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LINK = "linked_config_uri";
    private static final String KEY_LINK_NAME = "linked_config_name";
    private static final int MAX_CONFIG_BYTES = 256 * 1024;

    private Config() {
    }

    static File file(Context context) {
        return new File(context.getFilesDir(), "davmail.properties");
    }

    static File effectiveFile(Context context) {
        return new File(context.getNoBackupFilesDir(), "davmail-effective.properties");
    }

    static File logFile(Context context) {
        return new File(context.getNoBackupFilesDir(), "davmail.log");
    }

    /** Returns the config text, creating it from the bundled default on first use. */
    static synchronized String read(Context context) throws IOException {
        File f = file(context);
        if (!f.exists()) {
            try (InputStream in = context.getAssets().open("default.properties")) {
                write(context, new String(readAll(in), StandardCharsets.UTF_8));
            }
        }
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static synchronized void write(Context context, String text) throws IOException {
        File f = file(context);
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("Cannot replace " + f);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            if (buffer.size() + n > MAX_CONFIG_BYTES) {
                throw new IOException("Config file is larger than 256 KiB.");
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    static synchronized Uri linkedUri(Context context) {
        String value = prefs(context).getString(KEY_LINK, null);
        return value == null ? null : Uri.parse(value);
    }

    static String readDocument(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) { throw new IOException("Cannot open selected config file."); }
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (SecurityException e) {
            throw new IOException("Cannot read linked config. Select the file again to grant access.", e);
        }
    }

    static void link(Context context, Uri uri, int resultFlags) throws IOException {
        if (!"content".equals(uri.getScheme()) || (resultFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            throw new IOException("Select a document with persistent read access.");
        }
        // Provider I/O happens off the UI thread and outside the private-cache lock.
        ConfigReloader.validate(readDocument(context, uri));
        String name = "Selected config file";
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) { name = cursor.getString(0); }
        } catch (RuntimeException ignored) {
            // Display metadata is optional; document access above was already verified.
        }
        synchronized (Config.class) {
            Uri previous = linkedUri(context);
            try {
                context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                throw new IOException("This file provider cannot keep access. Import a copy instead.", e);
            }
            if (!prefs(context).edit().putString(KEY_LINK, uri.toString()).putString(KEY_LINK_NAME, name).commit()) {
                if (!uri.equals(previous)) { release(context, uri); }
                throw new IOException("Cannot save linked config selection.");
            }
            if (previous != null && !previous.equals(uri)) { release(context, previous); }
        }
    }

    static synchronized void unlink(Context context) throws IOException {
        Uri previous = linkedUri(context);
        if (!prefs(context).edit().remove(KEY_LINK).remove(KEY_LINK_NAME).commit()) {
            throw new IOException("Cannot clear linked config selection.");
        }
        if (previous != null) { release(context, previous); }
    }

    static synchronized void saveLocal(Context context, String text) throws IOException {
        ConfigReloader.validate(text);
        // Local editing is explicit; never write back to the user's linked document.
        String previous = read(context);
        write(context, text);
        try {
            unlink(context);
        } catch (IOException e) {
            write(context, previous);
            throw e;
        }
    }

    static String linkedName(Context context) {
        return prefs(context).getString(KEY_LINK_NAME, "Selected config file");
    }

    private static void release(Context context, Uri uri) {
        try {
            context.getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // A deleted document or a revoked permission is already detached.
        }
    }

    static ConfigReloader.Store store(Context context) {
        return new ConfigReloader.Store() {
            public String saved() throws IOException { return read(context); }
            public String source() {
                Uri uri = linkedUri(context);
                return uri == null ? null : uri.toString();
            }
            public String readSource(String source) throws IOException {
                return readDocument(context, Uri.parse(source));
            }
            public Integer applyIfCurrent(String source, String text, ConfigReloader.Starter starter) throws Exception {
                synchronized (Config.class) {
                    Uri current = linkedUri(context);
                    if (current == null || !source.equals(current.toString())) { return null; }
                    int port = starter.start(text);
                    write(context, text);
                    return port;
                }
            }
        };
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
