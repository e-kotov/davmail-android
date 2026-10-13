package io.github.ekotov.davmail;

import android.content.Context;
import android.content.SharedPreferences;

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
    static String read(Context context) throws IOException {
        File f = file(context);
        if (!f.exists()) {
            try (InputStream in = context.getAssets().open("default.properties")) {
                write(context, new String(readAll(in), StandardCharsets.UTF_8));
            }
        }
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void write(Context context, String text) throws IOException {
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
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
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
