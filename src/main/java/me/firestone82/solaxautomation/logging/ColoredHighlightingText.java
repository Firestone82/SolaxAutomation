package me.firestone82.solaxautomation.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;

public class ColoredHighlightingText extends ForegroundCompositeConverterBase<ILoggingEvent> {

    @Override
    protected String getForegroundColorCode(ILoggingEvent event) {
        return switch (event.getLevel().levelStr) {
            case "ERROR" -> "0;31";
            case "INFO" -> "0;29";
            case "WARN" -> "0;33";
            case "DEBUG" -> "0;35";
            case "TRACE" -> "0;34";
            default -> "0";
        };
    }
}
