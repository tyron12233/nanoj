package com.tyron.nanoj.testFramework;

import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.LogRecord;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Test-only logging setup.
 *
 * Controls java.util.logging output via system property:
 * - nanoj.test.logLevel=INFO|FINE|FINER|FINEST|WARNING|SEVERE
 */
public final class TestLogging {

    private static volatile boolean configured;

    private TestLogging() {
    }

    public static void configureOnce() {
        if (configured) return;
        configured = true;

        Formatter formatter = new CompactTestLogFormatter();

        String raw = System.getProperty("nanoj.test.logLevel", "INFO");
        Level level = parseLevel(raw);

        Logger root = Logger.getLogger("");
        root.setLevel(level);
        for (Handler h : root.getHandlers()) {
            h.setLevel(level);
            if (h instanceof ConsoleHandler) {
                h.setFormatter(formatter);
            }
        }

        root.log(Level.INFO, "testLogging configured level=" + level.getName());
    }

    private static final class CompactTestLogFormatter extends Formatter {

        private static final DateTimeFormatter TS = DateTimeFormatter
                .ofPattern("HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord record) {
            String ts = TS.format(Instant.ofEpochMilli(record.getMillis()));
            String level = padRight(record.getLevel().getName(), 7);

            String loggerName = record.getLoggerName();
            String logger = shortLoggerName(loggerName);

            String msg = formatMessage(record);

            StringBuilder out = new StringBuilder(128);
            out.append(ts).append(' ')
                    .append(level).append(' ')
                    .append(logger).append(" - ")
                    .append(msg)
                    .append('\n');

            Throwable t = record.getThrown();
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                out.append(sw);
            }

            return out.toString();
        }

        private static String shortLoggerName(String loggerName) {
            if (loggerName == null || loggerName.isBlank()) return "root";
            int lastDot = loggerName.lastIndexOf('.');
            String simple = lastDot >= 0 ? loggerName.substring(lastDot + 1) : loggerName;
            int dollar = simple.indexOf('$');
            return dollar >= 0 ? simple.substring(0, dollar) : simple;
        }

        private static String padRight(String s, int width) {
            if (s == null) s = "";
            if (s.length() >= width) return s;
            return s + " ".repeat(width - s.length());
        }
    }

    private static Level parseLevel(String raw) {
        if (raw == null) return Level.INFO;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return Level.parse(v);
        } catch (IllegalArgumentException ignored) {
            return Level.INFO;
        }
    }
}
