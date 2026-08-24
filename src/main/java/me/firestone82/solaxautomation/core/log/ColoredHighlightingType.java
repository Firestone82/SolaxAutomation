package me.firestone82.solaxautomation.core.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;

/**
 * Colours the log level column: bright, so the severity is the first thing the eye lands on.
 * Referenced from {@code logback-spring.xml} as {@code %levelColor}.
 */
public class ColoredHighlightingType extends ForegroundCompositeConverterBase<ILoggingEvent> {

    @Override
    protected String getForegroundColorCode(ILoggingEvent event) {
        return switch (event.getLevel().levelStr) {
            case "ERROR" -> "1;31";
            case "WARN" -> "1;33";
            case "INFO" -> "1;32";
            case "DEBUG" -> "1;35";
            case "TRACE" -> "1;34";
            default -> "0";
        };
    }
}
