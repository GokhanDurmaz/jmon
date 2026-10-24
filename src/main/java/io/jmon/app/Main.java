package io.jmon.app;

import io.jmon.core.MemoryTrendAnalyzer;
import io.jmon.core.MonitorConfiguration;
import io.jmon.core.HealthEvaluator;
import io.jmon.core.SystemTimeSource;
import io.jmon.tui.Tui;
import io.jmon.tui.TuiRenderer;
import io.jmon.spring.SpringBootMetricsClient;
import io.jmon.system.*;

import java.nio.file.Path;
import java.time.Duration;

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
                configureSpringBoot(MonitorConfiguration.builder(), args)
                        .build();

        SpringBootMetricsClient springBootClient =
                config.getSpringBootEndpoint().isPresent()
                        ? new SpringBootMetricsClient(config)
                        : null;

        Tui tui = new Tui(
                config,
                pid,
                new RealSystemMetricsCollector(),
                new TargetProcessCollector(),
                new ClassHistogramCollector(),
                new MemoryTrendAnalyzer(
                        SystemTimeSource.systemDefault(),
                        config.getLeakObservationWindow(),
                        6,
                        4
                ),
                new HealthEvaluator(),
                new TuiRenderer(),
                springBootClient,
                new DeepDiagnostics(config.getDiagnosticsDir())
        );
        tui.start();
    }

    static MonitorConfiguration.Builder configureSpringBoot(
            MonitorConfiguration.Builder builder, String[] args) {

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (arg.startsWith("--spring-endpoint=")) {
                builder.springBootEndpoint(
                        arg.substring("--spring-endpoint=".length()));
            } else if ("--spring-endpoint".equals(arg)
                    && i + 1 < args.length) {
                builder.springBootEndpoint(args[++i]);
            } else if (arg.startsWith("--spring-user=")) {
                builder.springBootUsername(
                        arg.substring("--spring-user=".length()));
            } else if ("--spring-user".equals(arg)
                    && i + 1 < args.length) {
                builder.springBootUsername(args[++i]);
            } else if (arg.startsWith("--spring-password=")) {
                builder.springBootPassword(
                        arg.substring("--spring-password=".length()));
            } else if ("--spring-password".equals(arg)
                    && i + 1 < args.length) {
                builder.springBootPassword(args[++i]);
            } else if (arg.startsWith("--leak-window-minutes=")) {
                builder.leakObservationWindow(parseWindowMinutes(
                        arg.substring("--leak-window-minutes=".length())));
            } else if ("--leak-window-minutes".equals(arg)
                    && i + 1 < args.length) {
                builder.leakObservationWindow(parseWindowMinutes(args[++i]));
            } else if (arg.startsWith("--diagnostics-dir=")) {
                builder.diagnosticsDir(Path.of(
                        arg.substring("--diagnostics-dir=".length())));
            } else if ("--diagnostics-dir".equals(arg)
                    && i + 1 < args.length) {
                builder.diagnosticsDir(Path.of(args[++i]));
            }
        }

        return builder;
    }

    private static Duration parseWindowMinutes(String value) {
        try {
            long minutes = Long.parseLong(value);
            if (minutes < 1 || minutes > 1440) {
                throw new IllegalArgumentException(
                        "--leak-window-minutes must be between 1 and 1440,"
                                + " got " + value);
            }
            return Duration.ofMinutes(minutes);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--leak-window-minutes expects a number of minutes,"
                            + " got " + value);
        }
    }

    private static void printUsage() {
        System.out.println("JMon - Java Terminal Monitor");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  jmon --pid <java-process-id> [options]");
        System.out.println("  jmon --version, -v");
        System.out.println("  jmon --help, -h");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --spring-endpoint <url>");
        System.out.println("          Monitor a Spring Boot application through its");
        System.out.println("          Actuator metrics endpoint (optional)");
        System.out.println("          Example: --spring-endpoint http://localhost:8080");
        System.out.println("  --spring-user <username>");
        System.out.println("          Actuator HTTP basic auth username (optional)");
        System.out.println("  --spring-password <password>");
        System.out.println("          Actuator HTTP basic auth password (optional)");
        System.out.println("  --leak-window-minutes <1..1440>");
        System.out.println("          Memory-leak analysis observation window in");
        System.out.println("          minutes (default 10)");
        System.out.println("  --diagnostics-dir <path>");
        System.out.println("          Output directory for heap dumps and JFR");
        System.out.println("          recordings (default: ./diagnostics)");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  jmon --pid 12345 --spring-endpoint http://localhost:8080");
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
