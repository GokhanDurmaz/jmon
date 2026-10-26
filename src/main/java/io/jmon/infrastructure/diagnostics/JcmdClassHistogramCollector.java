package io.jmon.infrastructure.diagnostics;

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

import io.jmon.application.port.out.ClassHistogramCollector;
import io.jmon.common.constants.MonitoringConstants;
import io.jmon.domain.memory.ClassMemoryStat;

/** Jcmd class histogram collector. */
public class JcmdClassHistogramCollector implements ClassHistogramCollector {


    /** Regex group holding histogram instance count. */
    private static final int HISTOGRAM_INSTANCES_GROUP = 1;

    /** Regex group holding histogram byte total. */
    private static final int HISTOGRAM_BYTES_GROUP = 2;

    /** Regex group holding histogram class name. */
    private static final int HISTOGRAM_CLASSNAME_GROUP = 3;

    private static final Pattern HISTOGRAM_ROW = Pattern.compile(
            "^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(.+?)\\s*$"
    );

    @Override
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
                    MonitoringConstants.HISTOGRAM_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
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

            if (process.exitValue() != 0) { // magic-exempt: process exit status 0 means success
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

                long instances = Long.parseLong(matcher.group(HISTOGRAM_INSTANCES_GROUP));
                long bytes = Long.parseLong(matcher.group(HISTOGRAM_BYTES_GROUP));
                String className = matcher.group(HISTOGRAM_CLASSNAME_GROUP).trim();

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
                    .limit(Math.max(1, limit)) // magic-exempt: smallest legal width/limit
                    .toList();

        } finally {
            Files.deleteIfExists(outputFile);
        }
    }
}
