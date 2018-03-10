package org.javafxports.jfxmirror;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;

public class LoggerListener extends ContextAwareBase implements LoggerContextListener, LifeCycle {
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final String USER_HOME = System.getProperty("user.home");
    private volatile boolean started = false;

    @Override
    public void start() {
        if (started) {
            return;
        }

        // The "log.file.name" and "log.file.path" properties allow for changing the name/path of the log file
        // dynamically via JVM parameters (e.g. -Dlog.file.name=my_log, -Dlog.file.path="C:\\logs")
        final String logFileBaseName = System.getProperty("log.file.name", "jfxmirror");
        final String logFilePath = System.getProperty("log.file.path", OS_NAME.contains("windows") ?
                USER_HOME + "\\jfxmirror\\log" : USER_HOME + "/jfxmirror/log");
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
