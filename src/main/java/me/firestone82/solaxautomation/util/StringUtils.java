package me.firestone82.solaxautomation.util;

import lombok.NonNull;

public class StringUtils {

    public static String parseArgs(@NonNull String message, @NonNull Object... args) {
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;

        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);

            if (c == '{' && i + 1 < message.length() && message.charAt(i + 1) == '}') {
                if (argIndex < args.length) {
                    sb.append(args[argIndex++]);
                }

                i++; // Skip the closing brace
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }
}
