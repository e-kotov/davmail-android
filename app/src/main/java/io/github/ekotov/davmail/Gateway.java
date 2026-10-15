package io.github.ekotov.davmail;

import davmail.Settings;
import davmail.caldav.CaldavServer;
import davmail.exchange.ExchangeSessionFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Runs DavMail's CalDAV listener in-process. DavGateway.main is not used because it touches
 * java.awt; only the CalDAV server is started.
 */
final class Gateway {
    private static CaldavServer server;
    private static Set<String> appliedKeys = new HashSet<>();

    private Gateway() {
    }

    static synchronized int start(File config, File effective, File logFile) throws Exception {
        stop();
        // Skip reload4j's default configuration, which needs java.beans.
        System.setProperty("log4j.defaultInitOverride", "true");

        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(config)) {
            properties.load(in);
        }
        applyOverrides(properties, logFile);

        // Remove missing keys: an absent calendar include list differs from an empty one.
        for (String key : appliedKeys) {
            if (!properties.containsKey(key)) {
                Settings.removeProperty(key);
            }
        }
        // Writes from DavMail (Settings.save) go to a scratch copy, not the user's file.
        Settings.setConfigFilePath(effective.getAbsolutePath());
        for (String key : properties.stringPropertyNames()) {
            Settings.setProperty(key, properties.getProperty(key));
        }
        appliedKeys = new HashSet<>(properties.stringPropertyNames());
        Settings.updateLoggingConfig();

        int port = Settings.getIntProperty("davmail.caldavPort");
        if (port <= 0) {
            throw new IOException("davmail.caldavPort is not set");
        }
        CaldavServer caldav = new CaldavServer(port);
        caldav.bind();
        caldav.start();
        server = caldav;
        return port;
    }

    static synchronized void stop() {
        if (server != null) {
            server.close();
            server = null;
            // Drops cached Exchange sessions and credentials.
            ExchangeSessionFactory.shutdown();
        }
    }

    private static void applyOverrides(Properties p, File logFile) {
        p.setProperty("davmail.server", "true");
        p.setProperty("davmail.enableTray", "false");
        p.setProperty("davmail.disableUpdateCheck", "true");
        p.setProperty("davmail.logFilePath", logFile.getAbsolutePath());
        if (!"true".equals(p.getProperty("davmail.allowRemote"))) {
            p.setProperty("davmail.bindAddress", "127.0.0.1");
        }
        // Only CalDAV runs here; the TLS server keystore is a desktop setting.
        for (String key : new String[]{"davmail.ssl.keystoreFile", "davmail.ssl.keystoreType",
                "davmail.ssl.keystorePass", "davmail.ssl.keyPass"}) {
            p.remove(key);
        }
        // WARN keeps event contents out of the log unless the config asks otherwise.
        for (String key : new String[]{"log4j.rootLogger", "log4j.logger.davmail",
                "log4j.logger.httpclient.wire", "log4j.logger.httpclient"}) {
            if (p.getProperty(key, "").isEmpty()) {
                p.setProperty(key, "WARN");
            }
        }
    }
}
