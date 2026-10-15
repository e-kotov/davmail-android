package io.github.ekotov.davmail;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.Objects;
import java.util.Properties;

/** Serial reload coordinator. A changed linked file must be stable across two polls. */
final class ConfigReloader {
    interface Starter { int start(String text) throws Exception; }
    interface Store {
        String saved() throws IOException;
        String source();
        String readSource(String source) throws IOException;
        Integer applyIfCurrent(String source, String text, Starter starter) throws Exception;
    }

    private final Store store;
    private final Starter starter;
    private String active;
    private String observedSource;
    private String pending;
    private String rejected;
    private int port;
    private String status = "Stopped";

    ConfigReloader(Store store, Starter starter) {
        this.store = store;
        this.starter = starter;
    }

    void start() {
        pending = null;
        rejected = null;
        observedSource = null;
        try {
            String saved = store.saved();
            validate(saved);
            port = starter.start(saved);
            active = saved;
            status = listening();
        } catch (Exception e) {
            if (active != null) {
                try {
                    port = starter.start(active);
                    status = "Saved update rejected. Keeping last working config.";
                } catch (Exception rollback) {
                    active = null;
                    status = "Could not start saved config. Review settings and start again.";
                }
            } else {
                status = "Could not start saved config. Review settings and start again.";
            }
        }
        poll();
    }

    void poll() {
        String source = store.source();
        boolean sourceChanged = !Objects.equals(source, observedSource);
        if (sourceChanged) {
            observedSource = source;
            pending = null;
            rejected = null;
        }
        if (source == null) {
            if (sourceChanged && active != null) { status = listening(); }
            return;
        }
        String text;
        try {
            text = store.readSource(source);
            validate(text);
        } catch (Exception e) {
            pending = null;
            status = fallback("Linked file unreadable or invalid. Relink it if access was lost.");
            return;
        }
        if (text.equals(active)) {
            pending = null;
            rejected = null;
            status = listening();
            return;
        }
        if (text.equals(rejected)) { return; }
        if (!text.equals(pending)) {
            pending = text;
            status = fallback("Waiting for linked file to settle.");
            return;
        }
        pending = null;
        try {
            Integer changedPort = store.applyIfCurrent(source, text, starter);
            if (changedPort != null) {
                port = changedPort;
                active = text;
                rejected = null;
                status = listening();
            }
        } catch (Exception e) {
            rejected = text;
            if (active != null) {
                try {
                    port = starter.start(active);
                    status = fallback("Linked update rejected.");
                } catch (Exception rollback) {
                    active = null;
                    status = "Linked update failed; saved config is retained. Review settings and start again.";
                }
            } else {
                status = "Linked update failed. Review settings and start again.";
            }
        }
    }

    String status() { return status; }

    private String listening() {
        return "Listening on 127.0.0.1:" + port + (store.source() == null ? "" : " · linked config");
    }

    private String fallback(String message) {
        return active == null ? message + " Gateway is not running." : message + " Keeping last working config.";
    }

    static void validate(String text) throws IOException {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
            int port = Integer.parseInt(p.getProperty("davmail.caldavPort", "").trim());
            if (port < 1 || port > 65535) { throw new IllegalArgumentException(); }
            URI url = URI.create(p.getProperty("davmail.url", "").trim());
            if ((!"https".equalsIgnoreCase(url.getScheme()) && !"http".equalsIgnoreCase(url.getScheme()))
                    || url.getHost() == null) { throw new IllegalArgumentException(); }
        } catch (IllegalArgumentException e) {
            throw new IOException("Config needs a valid davmail.url and davmail.caldavPort (1–65535).", e);
        }
    }
}
