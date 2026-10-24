package io.jmon.system;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives presentation-safe identity details from a JVM class histogram
 * entry. Only data the histogram actually provides is exposed; nothing is
 * inferred beyond what is derivable from the class name itself.
 */
public final class ClassIdentity {

    private static final Pattern MODULE_SUFFIX = Pattern.compile("\\((.+?)\\)\\s*$");

    private ClassIdentity() {
    }

    /** The histogram name exactly as received (never rewritten). */
    public static String fullName(ClassHistogramCollector.ClassMemoryStat stat) {
        return stat.className();
    }

    /** The binary class name without the optional module suffix. */
    public static String baseName(ClassHistogramCollector.ClassMemoryStat stat) {
        Matcher matcher = MODULE_SUFFIX.matcher(stat.className());
        int end = matcher.find()
                ? matcher.start()
                : stat.className().length();
        return stat.className().substring(0, end).trim();
    }

    /** The module name/version suffix, only when the histogram provides one. */
    public static Optional<String> moduleInfo(ClassHistogramCollector.ClassMemoryStat stat) {
        Matcher matcher = MODULE_SUFFIX.matcher(stat.className());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static boolean isArray(ClassHistogramCollector.ClassMemoryStat stat) {
        return baseName(stat).startsWith("[");
    }

    /** The fully decoded array type (for example int[][]), only for array descriptors. */
    public static Optional<String> arrayType(ClassHistogramCollector.ClassMemoryStat stat) {
        return arrayComponent(stat)
                .map(base -> base + "[]");
    }

    /** The element type of the outermost array (without its own [] levels). */
    public static Optional<String> arrayComponent(ClassHistogramCollector.ClassMemoryStat stat) {
        String name = baseName(stat);

        if (!name.startsWith("[")) {
            return Optional.empty();
        }

        int depth = 0;

        while (depth < name.length() && name.charAt(depth) == '[') {
            depth++;
        }

        String element = name.substring(depth);
        String elementType;

        if (element.startsWith("L") && element.endsWith(";")) {
            elementType = element.substring(1, element.length() - 1);
        } else {
            elementType = switch (element) {
                case "B" -> "byte";
                case "C" -> "char";
                case "D" -> "double";
                case "F" -> "float";
                case "I" -> "int";
                case "J" -> "long";
                case "S" -> "short";
                case "Z" -> "boolean";
                default -> null;
            };
        }

        if (elementType == null) {
            return Optional.empty();
        }

        if (depth > 1) {
            elementType += "[]" .repeat(depth - 1);
        }

        return Optional.of(elementType);
    }

    /**
     * Classification is only reliable while the class came from a named JDK
     * module (java.* or jdk.*) that the histogram itself reported.
     */
    public static boolean isJdkClass(ClassHistogramCollector.ClassMemoryStat stat) {
        return moduleInfo(stat)
                .map(ClassIdentity::isJdkModule)
                .orElse(false);
    }

    private static boolean isJdkModule(String moduleInfo) {
        return moduleInfo.startsWith("java.")
                || moduleInfo.startsWith("jdk.");
    }

    /** Average shallow heap per instance; absent for zero-instance entries. */
    public static java.util.OptionalLong avgShallow(ClassHistogramCollector.ClassMemoryStat stat) {
        if (stat.instances() <= 0) {
            return java.util.OptionalLong.empty();
        }

        return java.util.OptionalLong.of(
                Math.max(0L, Math.round((double) stat.bytes() / stat.instances()))
        );
    }
}
