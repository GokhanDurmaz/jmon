package io.jmon.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClassHistogramCollector {

    private static final Pattern HISTOGRAM_ROW = Pattern.compile(
            "^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(.+?)\\s*$"
    );

    public record ClassMemoryStat(
            String className,
            long instances,
            long bytes
    ) {}

    public List<ClassMemoryStat> collect(int pid, int limit)
            throws Exception {

        Path jcmd = Path.of(
                System.getProperty("java.home"),
                "bin",
                "jcmd"
        );

        if (!Files.isExecutable(jcmd)) {
            throw new IOException(
                    "jcmd not found or not executable: " + jcmd
            );
        }

        Path outputFile = Files.createTempFile(
                "jmon-histogram-", ".txt"
        );

        try {
            Process process = new ProcessBuilder(
                    jcmd.toString(),
                    Integer.toString(pid),
                    "GC.class_histogram",
                    "-all"
            )
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();

            boolean completed = process.waitFor(
                    20, TimeUnit.SECONDS
            );

            if (!completed) {
                process.destroyForcibly();
                throw new IOException(
                        "Class histogram timed out after 20 seconds"
                );
            }

            String output = Files.readString(
                    outputFile,
                    StandardCharsets.UTF_8
            );

            if (process.exitValue() != 0) {
                throw new IOException(
                        "jcmd failed: " + output.trim()
                );
            }

            List<ClassMemoryStat> stats = new ArrayList<>();

            for (String line : output.split("\\R")) {
                Matcher matcher = HISTOGRAM_ROW.matcher(line);

                if (!matcher.matches()) {
                    continue;
                }

                long instances = Long.parseLong(matcher.group(1));
                long bytes = Long.parseLong(matcher.group(2));
                String className = matcher.group(3).trim();

                stats.add(new ClassMemoryStat(
                        className,
                        instances,
                        bytes
                ));
            }

            return stats.stream()
                    .sorted(Comparator.comparingLong(
                            ClassMemoryStat::bytes
                    ).reversed())
                    .limit(Math.max(1, limit))
                    .toList();

        } finally {
            Files.deleteIfExists(outputFile);
        }
    }
}
