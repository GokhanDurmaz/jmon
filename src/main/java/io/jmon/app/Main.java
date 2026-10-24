package io.jmon.app;

import io.jmon.core.MonitorConfiguration;
import io.jmon.core.HealthEvaluator;
import io.jmon.tui.Tui;
import io.jmon.tui.TuiRenderer;
import io.jmon.spring.SpringBootMetricsClient;
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
                new MemoryLeakDetector(),
                new HealthEvaluator(),
                new TuiRenderer(),
                springBootClient
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
            }
        }

        return builder;
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
