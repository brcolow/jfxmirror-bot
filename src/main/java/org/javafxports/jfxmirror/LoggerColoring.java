package org.javafxports.jfxmirror;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ANSIConstants;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;

/**
 * Controls the colors that are used for the different log levels when logging to stdout.
 * <p>
 * Based on: https://github.com/shuwada/logback-custom-color
 */
public class LoggerColoring extends ForegroundCompositeConverterBase<ILoggingEvent> {
    @Override
    protected String getForegroundColorCode(ILoggingEvent event) {
        Level level = event.getLevel();
        switch (level.toInt()) {
            case Level.ERROR_INT:
                return ANSIConstants.RED_FG; // Used on stdout for error messages.
            case Level.WARN_INT:
                return ANSIConstants.YELLOW_FG; // Use on stdout for warning messages.
            case Level.INFO_INT:
                return ANSIConstants.GREEN_FG; // Used on stdout for success messages.
            case Level.DEBUG_INT:
                return ANSIConstants.DEFAULT_FG; // Used on stdout for general messages.
            default:
                return ANSIConstants.DEFAULT_FG;
        }
    }
}
