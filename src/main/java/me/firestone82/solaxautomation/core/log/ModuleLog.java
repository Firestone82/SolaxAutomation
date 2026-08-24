package me.firestone82.solaxautomation.core.log;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.function.Function;

/**
 * Small logging facade that gives every module the same, easily scannable log shape.
 * <p>
 * A single run of a module reads like a short report:
 * <pre>
 * ══════════════════════════════════════════════════════════════
 *  [force-discharge] Evaluating discharge window for today
 * ──────────────────────────────────────────────────────────────
 *    · Peak slot .............. 19:15-19:30 @ 4.85 CZK/kWh
 *    · Battery ................ 87 % (min 80 %)
 *    → Armed 18:45 -> 20:15 (6 slots, avg 4.31 CZK/kWh)
 * </pre>
 * Every line is prefixed with the module id, so grepping a single module out of a
 * shared log file is always possible even when runs interleave.
 */
@Slf4j
public class ModuleLog {

    private static final int LINE_WIDTH = 78;
    private static final int LABEL_WIDTH = 26;

    private final Logger logger;
    private final String moduleId;

    private ModuleLog(Logger logger, String moduleId) {
        this.logger = logger;
        this.moduleId = moduleId;
    }

    public static ModuleLog of(Class<?> owner, String moduleId) {
        return new ModuleLog(LoggerFactory.getLogger(owner), moduleId);
    }

    // ---------------------------------------------------------------- sections

    /** Opens a new run block: a heavy rule, the title, then a light rule. */
    public void header(String message, Object... args) {
        logger.info("═".repeat(LINE_WIDTH));
        logger.info("{} {}", tag(), format(message, args));
        logger.info("─".repeat(LINE_WIDTH));
    }

    // ---------------------------------------------------------------- content

    /** Aligned {@code label ....... value} detail line. */
    public void detail(String label, String value, Object... args) {
        logger.info("{}   · {} {}", tag(), pad(label), format(value, args));
    }

    /** Renders a collection as an indented list under a title. */
    public <T> void list(String title, Collection<T> items, Function<T, String> renderer) {
        logger.info("{}   · {}", tag(), title);
        items.forEach(item -> logger.info("{}       | {}", tag(), renderer.apply(item)));
    }

    /** A decision the module took, and the action it triggers. */
    public void action(String message, Object... args) {
        logger.info("{}   → {}", tag(), format(message, args));
    }

    /** A successfully applied change. */
    public void success(String message, Object... args) {
        logger.info("{}   ✓ {}", tag(), format(message, args));
    }

    /** The module deliberately did nothing, with the reason why. */
    public void noAction(String reason, Object... args) {
        logger.info("{}   = No action - {}", tag(), format(reason, args));
    }

    /** The run was abandoned early; {@code reason} must explain what was missing. */
    public void abort(String reason, Object... args) {
        logger.warn("{}   ✗ Aborted - {}", tag(), format(reason, args));
    }

    public void info(String message, Object... args) {
        logger.info("{} {}", tag(), format(message, args));
    }

    public void warn(String message, Object... args) {
        logger.warn("{} {}", tag(), format(message, args));
    }

    public void error(String message, Object... args) {
        logger.error("{}   ✗ {}", tag(), format(message, args));
    }

    public void error(Throwable throwable, String message, Object... args) {
        logger.error("{}   ✗ {}", tag(), format(message, args), throwable);
    }

    public void debug(String message, Object... args) {
        logger.debug("{} {}", tag(), format(message, args));
    }

    // ---------------------------------------------------------------- helpers

    private String tag() {
        return "[" + moduleId + "]";
    }

    private static String pad(String label) {
        if (label.length() >= LABEL_WIDTH) {
            return label;
        }

        return label + " " + ".".repeat(LABEL_WIDTH - label.length() - 1);
    }

    /** SLF4J-style {} substitution, so callers keep one placeholder syntax everywhere. */
    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }

        StringBuilder sb = new StringBuilder(message.length() + 32);
        int argIndex = 0;

        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);

            if (c == '{' && i + 1 < message.length() && message.charAt(i + 1) == '}' && argIndex < args.length) {
                sb.append(args[argIndex++]);
                i++;
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }
}
