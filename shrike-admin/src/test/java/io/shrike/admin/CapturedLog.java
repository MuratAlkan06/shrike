package io.shrike.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Every line one class wrote while a test was watching, with whatever throwable was attached to each.
 *
 * <p>How much a failure costs an operator's disk is behaviour, so it is asserted rather than reviewed:
 * this is what lets a test say that a status a stranger can ask for a million times writes one line
 * and not fifty stack frames. It listens where this facade's own code writes — {@code System.Logger},
 * which the JDK backs with {@code java.util.logging} when nothing else claims it — so it sees the
 * record as it was made, before any bridge or appender has decided whether to print it. The level is
 * opened to {@link Level#ALL} for the duration, because a line written at DEBUG is still a line, and
 * put back as it was on close.
 *
 * <p><strong>Attach it after the facade has started.</strong> Spring Boot rearranges the logging
 * system as it comes up, and a handler added before that is a handler added to a configuration that is
 * about to be replaced.
 */
final class CapturedLog implements AutoCloseable {

    private final Logger writer;
    private final Level levelBeforeCapture;
    private final Handler capture;

    /** Written by whichever request thread logged and read by the test's, so it is synchronized. */
    private final List<LogRecord> lines = Collections.synchronizedList(new ArrayList<>());

    private CapturedLog(Logger writer) {
        this.writer = writer;
        this.levelBeforeCapture = writer.getLevel();
        this.capture = new Capture();
    }

    /**
     * @param writingClass the class whose lines to keep, named the way it names its own logger
     * @return the capture, which the caller closes to put the logger back as it found it
     */
    static CapturedLog of(Class<?> writingClass) {
        CapturedLog captured = new CapturedLog(Logger.getLogger(writingClass.getName()));
        captured.writer.setLevel(Level.ALL);
        captured.writer.addHandler(captured.capture);
        return captured;
    }

    /**
     * @return every line written since this capture was attached, in the order they were written
     */
    List<LogRecord> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    @Override
    public void close() {
        writer.removeHandler(capture);
        writer.setLevel(levelBeforeCapture);
    }

    /** Keeps the record whole — level, message, and throwable — rather than formatting it. */
    private final class Capture extends Handler {

        @Override
        public void publish(LogRecord line) {
            lines.add(line);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
