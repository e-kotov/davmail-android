package io.github.ekotov.davmail;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public class MainActivityTest {
    @Test public void copyAndLinkedPickersRequestOnlyNecessaryAccess() {
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            button(activity, R.string.import_file).performClick();
            var copy = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, copy.getAction());
            assertEquals(0, copy.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            button(activity, R.string.link_file).performClick();
            var link = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, link.getAction());
            assertNotEquals(0, link.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            assertNotEquals(0, link.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            assertEquals(0, link.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
    }

    @Test public void linkedEditorIsReadOnlyAndDetachRestoresLocalEditing() throws Exception {
        var app = RuntimeEnvironment.getApplication();
        String text = "davmail.url=https://example.org/EWS/Exchange.asmx\ndavmail.caldavPort=11080\n";
        Config.saveLocal(app, text);
        Uri uri = Uri.parse("content://test.documents/config.properties");
        Shadows.shadowOf(app.getContentResolver()).registerInputStreamSupplier(uri,
                () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        Config.link(app, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            EditText editor = first(activity.findViewById(android.R.id.content), EditText.class);
            assertFalse(editor.isEnabled());
            button(activity, R.string.unlink_file).performClick();
            assertTrue(editor.isEnabled());
            assertNull(Config.linkedUri(app));
            assertEquals(text, Config.read(app));
        }
    }

    private static Button button(MainActivity activity, int label) {
        return findButton(activity.findViewById(android.R.id.content), activity.getString(label));
    }
    private static Button findButton(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) { return (Button) view; }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = findButton(group.getChildAt(i), text);
                if (result != null) { return result; }
            }
        }
        return null;
    }
    private static <T extends View> T first(View view, Class<T> type) {
        if (type.isInstance(view)) { return type.cast(view); }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T result = first(group.getChildAt(i), type);
                if (result != null) { return result; }
            }
        }
        return null;
    }
}
