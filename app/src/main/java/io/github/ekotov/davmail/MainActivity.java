package io.github.ekotov.davmail;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;

/** Status, config editor and controls. Built in code to keep the app free of AndroidX. */
public class MainActivity extends Activity {
    private static final int REQUEST_IMPORT = 1;
    private static final int REQUEST_LINK = 2;
    private final ExecutorService files = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView logView;
    private EditText configView;
    private TextView sourceView;
    private Button saveButton;
    private Button detachButton;
    private String displayedCache;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            statusView.setText(getString(R.string.status, GatewayService.status, BuildConfig.DAVMAIL_VERSION));
            refreshSource();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        root.addView(statusView);

        TextView help = new TextView(this);
        help.setText(R.string.help);
        help.setTextIsSelectable(true);
        help.setPadding(0, pad, 0, pad);
        root.addView(help);

        sourceView = new TextView(this);
        root.addView(sourceView);
        saveButton = button(R.string.save_start, v -> saveAndStart());
        root.addView(saveButton);
        root.addView(button(R.string.stop, v -> {
            GatewayService.stop(this);
            toast(R.string.stopped);
        }));
        root.addView(button(R.string.import_file, v -> pickFile(REQUEST_IMPORT)));
        root.addView(button(R.string.link_file, v -> pickFile(REQUEST_LINK)));
        detachButton = button(R.string.unlink_file, v -> {
            try {
                Config.unlink(this);
                configView.setText(Config.read(this));
                refreshSource();
                toast(R.string.unlinked);
            } catch (IOException e) { toast(e.getMessage()); }
        });
        root.addView(detachButton);
        root.addView(button(R.string.battery, v -> requestBatteryExemption()));
        root.addView(button(R.string.show_log, v -> showLog()));

        configView = new EditText(this);
        configView.setTypeface(Typeface.MONOSPACE);
        configView.setTextSize(12);
        configView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        configView.setHorizontallyScrolling(false);
        root.addView(configView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        try {
            configView.setText(Config.read(this));
        } catch (IOException e) {
            toast(e.getMessage());
        }
        if (savedInstanceState != null && Config.linkedUri(this) == null) {
            configView.setText(savedInstanceState.getString("editor", configView.getText().toString()));
        }
        refreshSource();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private Button button(int label, android.view.View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private void saveAndStart() {
        try {
            if (Config.linkedUri(this) == null) {
                Config.saveLocal(this, configView.getText().toString());
            }
            GatewayService.start(this);
            toast(R.string.saved);
        } catch (IOException e) {
            toast(e.getMessage());
        }
    }

    private void pickFile(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (request == REQUEST_LINK) { intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); }
        startActivityForResult(intent, request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQUEST_IMPORT && requestCode != REQUEST_LINK)
                || resultCode != RESULT_OK || data == null || data.getData() == null) { return; }
        Uri uri = data.getData();
        files.execute(() -> {
            try {
                if (requestCode == REQUEST_LINK) {
                    Config.link(this, uri, data.getFlags());
                    runOnUiThread(() -> {
                        if (isDestroyed()) { return; }
                        refreshSource();
                        GatewayService.start(this);
                        toast(R.string.linked);
                    });
                } else {
                    String text = Config.readDocument(this, uri);
                    Config.unlink(this);
                    runOnUiThread(() -> {
                        if (isDestroyed()) { return; }
                        refreshSource();
                        configView.setText(text);
                        toast(R.string.imported);
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) { toast("Cannot open config: " + e.getMessage()); }
                });
            }
        });
    }

    private void refreshSource() {
        boolean linked = Config.linkedUri(this) != null;
        sourceView.setText(linked ? getString(R.string.linked_source, Config.linkedName(this))
                : getString(R.string.local_source));
        configView.setEnabled(!linked);
        saveButton.setText(linked ? R.string.reload_linked : R.string.save_start);
        detachButton.setVisibility(linked ? View.VISIBLE : View.GONE);
        if (linked) {
            try {
                String cached = Config.read(this);
                if (!cached.equals(displayedCache)) { configView.setText(cached); }
                displayedCache = cached;
            } catch (IOException ignored) { /* Service status reports a read failure. */ }
        } else {
            displayedCache = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("editor", configView.getText().toString());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        files.shutdownNow();
        super.onDestroy();
    }

    private void requestBatteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast(R.string.battery_ok);
            return;
        }
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
    }

    private void showLog() {
        // Last 8 KB only; the log is at WARN by default.
        try (RandomAccessFile f = new RandomAccessFile(Config.logFile(this), "r")) {
            long start = Math.max(0, f.length() - 8192);
            byte[] tail = new byte[(int) (f.length() - start)];
            f.seek(start);
            f.readFully(tail);
            logView.setText(tail.length == 0 ? getString(R.string.log_empty) : new String(tail, StandardCharsets.UTF_8));
        } catch (IOException e) {
            logView.setText(R.string.log_empty);
        }
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
