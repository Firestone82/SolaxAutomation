package me.firestone82.solaxautomation.core.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;

/**
 * Colours the message itself: dim, so warnings and errors stand out from the normal flow
 * without every line shouting. Referenced from {@code logback-spring.xml} as {@code %messageColor}.
 */
public class ColoredHighlightingText extends ForegroundCompositeConverterBase<ILoggingEvent> {

    @Override
    protected String getForegroundColorCode(ILoggingEvent event) {
        return switch (event.getLevel().levelStr) {
            case "ERROR" -> "0;31";
            case "WARN" -> "0;33";
            case "INFO" -> "0;29";
            case "DEBUG" -> "0;35";
            case "TRACE" -> "0;34";
            default -> "0";
        };
    }
}
