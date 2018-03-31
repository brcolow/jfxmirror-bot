package org.javafxports.jfxmirror;

import java.io.File;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;

/**
 * Allows for using variables in the "logback.xml" configuration that can in turn be set by
 * system properties. This makes the logger easily configurable by the person running
 * jfxmirror_bot. Currently the name and location of the log can be configured otherwise
 * sensible platform-specific defaults are chosen.
 */
public class LoggerListener extends ContextAwareBase implements LoggerContextListener, LifeCycle {
    private volatile boolean started = false;

    @Override
    public void start() {
        if (started) {
            return;
        }

        // The "log.file.name" and "log.file.path" properties allow for changing the name/path of the log file
        // dynamically via JVM parameters (e.g. -Dlog.file.name=my_log, -Dlog.file.path="C:\\logs")
        final String logFileBaseName = System.getProperty("log.file.name", "jfxmirror");
        final String logFilePath = System.getProperty("log.file.path", File.separatorChar + "jfxmirror" +
                File.separatorChar + "log");
        Context context = getContext();
        context.putProperty("LOG_FILE_PATH", logFilePath);
        context.putProperty("LOG_FILE_BASE_NAME", logFileBaseName);
        started = true;
    }

    @Override
    public void stop() {
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public boolean isResetResistant() {
        return false;
    }

    @Override
    public void onStart(LoggerContext context) {}

    @Override
    public void onReset(LoggerContext context) {}

    @Override
    public void onStop(LoggerContext context) {}

    @Override
    public void onLevelChange(Logger logger, Level level) {}
}
