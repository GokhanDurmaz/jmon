package io.jmon.app;

import java.nio.file.Path;
import java.time.Duration;

import io.jmon.application.MonitorConfiguration;
import io.jmon.application.port.out.SpringBootMetricsClient;
import io.jmon.domain.health.HealthEvaluator;
import io.jmon.domain.memory.MemoryTrendAnalyzer;
import io.jmon.domain.memory.SystemTimeSource;
import io.jmon.infrastructure.actuator.HttpSpringBootMetricsClient;
import io.jmon.infrastructure.diagnostics.JcmdClassHistogramCollector;
import io.jmon.infrastructure.diagnostics.JcmdDeepDiagnostics;
import io.jmon.infrastructure.jvm.AttachJvmConnectionSource;
import io.jmon.infrastructure.system.OshiTargetProcessCollector;
import io.jmon.infrastructure.system.RealSystemMetricsCollector;
import io.jmon.infrastructure.update.UpdateManager;
import io.jmon.presentation.tui.Tui;
import io.jmon.presentation.tui.view.TuiRenderer;

/** Main . */
public class Main {

    /** Exit code signalling invalid CLI usage (non-zero, non-doc). */
    private static final int USAGE_ERROR_EXIT_CODE = 2;

    /** Exit code signalling success. */
    private static final int SUCCESS_EXIT_CODE = 0;

    /** Exit code signalling a failed self-update attempt. */
    private static final int UPDATE_FAILED_EXIT_CODE = 1;

    /** Smallest accepted leak-observation window in minutes. */
    private static final int MIN_LEAK_WINDOW_MINUTES = 1;

    /** Largest accepted leak-observation window (one day, minutes). */
    private static final int MAX_LEAK_WINDOW_MINUTES = 1440;

    /** Historic samples a leak evaluation must cover before verdicts. */
    private static final int LEAK_MIN_HISTORY_SAMPLES = 6;

    /** Corroboration samples required per history window by the analyzer. */
    private static final int LEAK_MIN_CORROBORATION_SAMPLES = 4;

    /** CLI entry point orchestrating the monitoring session. */
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

            if ("--update".equals(arg) || "-u".equals(arg)) {
                System.exit(performUpdate(new UpdateManager()));
                return;
            }
        }

        Integer pid = parsePid(args);

        if (pid == null || pid <= 0) { // magic-exempt: zero-mode comparison (e.g. pointer position)
            printUsage();
            System.exit(USAGE_ERROR_EXIT_CODE);
            return;
        }

        MonitorConfiguration config =
                configureSpringBoot(MonitorConfiguration.builder(), args)
                        .build();

        SpringBootMetricsClient springBootClient =
                config.getSpringBootEndpoint().isPresent()
                        ? new HttpSpringBootMetricsClient(config)
                        : null;

        Tui tui = new Tui(
                config,
                pid,
                new RealSystemMetricsCollector(),
                new AttachJvmConnectionSource(),
                new OshiTargetProcessCollector(),
                new JcmdClassHistogramCollector(),
                new MemoryTrendAnalyzer(
                        SystemTimeSource.systemDefault(),
                        config.getLeakObservationWindow(),
                        LEAK_MIN_HISTORY_SAMPLES,
                        LEAK_MIN_CORROBORATION_SAMPLES
                ),
                new HealthEvaluator(),
                new TuiRenderer(),
                springBootClient,
                new JcmdDeepDiagnostics(config.getDiagnosticsDir())
        );
        tui.start();
    }

    /**
     * Runs the self-update flow and reports the outcome on stdout.
     *
     * @param updateManager update orchestration
     * @return process exit code
     */
    static int performUpdate(UpdateManager updateManager) {
        UpdateManager.UpdateOutcome outcome = updateManager.performUpdate();
        System.out.println(outcome.message());
        switch (outcome.state()) {
            case COMPLETED:
            case UP_TO_DATE:
            case NO_RELEASE:
                return SUCCESS_EXIT_CODE;
            case FAILED:
            default:
                return UPDATE_FAILED_EXIT_CODE;
        }
    }

    static MonitorConfiguration.Builder configureSpringBoot(
            MonitorConfiguration.Builder builder, String[] args) {

        for (int i = 0; i < args.length; i++) { // magic-exempt: ordinary loop start index
            String arg = args[i];

            if (arg.startsWith("--spring-endpoint=")) {
                builder.springBootEndpoint(
                        arg.substring("--spring-endpoint=".length()));
            } else if ("--spring-endpoint".equals(arg)
                    && i + 1 < args.length) { // magic-exempt: next-occurrence index arithmetic
                builder.springBootEndpoint(args[++i]);
            } else if (arg.startsWith("--spring-user=")) {
                builder.springBootUsername(
                        arg.substring("--spring-user=".length()));
            } else if ("--spring-user".equals(arg)
                    && i + 1 < args.length) { // magic-exempt: next-occurrence index arithmetic
                builder.springBootUsername(args[++i]);
            } else if (arg.startsWith("--spring-password=")) {
                builder.springBootPassword(
                        arg.substring("--spring-password=".length()));
            } else if ("--spring-password".equals(arg)
                    && i + 1 < args.length) { // magic-exempt: next-occurrence index arithmetic
                builder.springBootPassword(args[++i]);
            } else if (arg.startsWith("--leak-window-minutes=")) {
                builder.leakObservationWindow(parseWindowMinutes(
                        arg.substring("--leak-window-minutes=".length())));
            } else if ("--leak-window-minutes".equals(arg)
                    && i + 1 < args.length) { // magic-exempt: next-occurrence index arithmetic
                builder.leakObservationWindow(parseWindowMinutes(args[++i]));
            } else if (arg.startsWith("--diagnostics-dir=")) {
                builder.diagnosticsDir(Path.of(
                        arg.substring("--diagnostics-dir=".length())));
            } else if ("--diagnostics-dir".equals(arg)
                    && i + 1 < args.length) { // magic-exempt: next-occurrence index arithmetic
                builder.diagnosticsDir(Path.of(args[++i]));
            }
        }

        return builder;
    }

    private static Duration parseWindowMinutes(String value) {
        try {
            long minutes = Long.parseLong(value);
            if (minutes < MIN_LEAK_WINDOW_MINUTES
                    || minutes > MAX_LEAK_WINDOW_MINUTES) {
                throw new IllegalArgumentException(
                        "--leak-window-minutes must be between 1 and 1440,"
                                + " got " + value);
            }
            return Duration.ofMinutes(minutes);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--leak-window-minutes expects a number of minutes,"
                            + " got " + value, e);
        }
    }

    private static void printUsage() {
        System.out.println("JMon - Java Terminal Monitor");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  jmon --pid <java-process-id> [options]");
        System.out.println("  jmon --version, -v");
        System.out.println("  jmon --help, -h");
        System.out.println("  jmon --update, -u");
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
        for (int i = 0; i < args.length; i++) { // magic-exempt: ordinary loop start index
            if ("--pid".equals(args[i]) && i + 1 < args.length) { // magic-exempt: next-occurrence index arithmetic
                try {
                    return Integer.parseInt(args[i + 1]); // magic-exempt: next-occurrence index arithmetic
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
