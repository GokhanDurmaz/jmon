package io.jmon.app;

import io.jmon.core.MonitorConfiguration;
import io.jmon.core.HealthEvaluator;
import io.jmon.tui.Tui;
import io.jmon.tui.TuiRenderer;
import io.jmon.system.*;

public class Main {

    public static void main(String[] args) {
        String version = System.getProperty("jmon.version", "development");

        for (String arg : args) {
            if ("--version".equals(arg) || "-v".equals(arg)) {
                System.out.println("JMon " + version);
                return;
            }

            if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                return;
            }
        }

        Integer pid = parsePid(args);

        if (pid == null || pid <= 0) {
            printUsage();
            System.exit(2);
            return;
        }

        MonitorConfiguration config =
                MonitorConfiguration.builder().build();

        Tui tui = new Tui(
                config,
                pid,
                new RealSystemMetricsCollector(),
                new TargetProcessCollector(),
                new ClassHistogramCollector(),
                new MemoryLeakDetector(),
                new HealthEvaluator(),
                new TuiRenderer()
        );
        tui.start();
    }

    private static void printUsage() {
        System.out.println("JMon - Java Terminal Monitor");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  jmon --pid <java-process-id>");
        System.out.println("  jmon --version, -v");
        System.out.println("  jmon --help, -h");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  jmon --pid 12345");
    }

    private static Integer parsePid(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--pid".equals(args[i]) && i + 1 < args.length) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }

            if (args[i].startsWith("--pid=")) {
                try {
                    return Integer.parseInt(
                            args[i].substring("--pid=".length())
                    );
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }

        return null;
    }
}
