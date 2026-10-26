package io.jmon.domain.health;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import io.jmon.domain.metrics.JvmMetrics;
import io.jmon.domain.metrics.ProcessMetrics;
import io.jmon.domain.metrics.SystemMetrics;

class HealthEvaluatorTest {

    private static final HealthEvaluator EVALUATOR = new HealthEvaluator();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static SystemMetrics system(Double cpu, Long totalMemory,
            Long freeMemory) {
        return new SystemMetrics(
                NOW,
                Optional.ofNullable(cpu),
                Optional.ofNullable(totalMemory),
                Optional.ofNullable(freeMemory),
                Optional.empty(),
                Optional.empty());
    }

    private static ProcessMetrics process(double cpuUtilization) {
        return new ProcessMetrics(NOW, 1000, "jmon", cpuUtilization,
                10_000_000L, "tester");
    }

    private static JvmMetrics heap(long used, long max) {
        return new JvmMetrics("runtime", "17", 1L, used, max, max,
                1L, 4, 4, 100, 1L, 10L);
    }

    private static HealthReport evaluateHealthy() {
        return EVALUATOR.evaluate(
                system(0.10, 1_000_000L, 500_000L),
                process(0.10),
                heap(100, 1000));
    }

    @Test
    void all_metrics_below_warning_thresholds_report_ok() {
        HealthReport report = evaluateHealthy();

        assertEquals(HealthStatus.OK, report.status());
        assertEquals(List.of(), report.alerts());
    }

    @Test
    void value_exactly_at_warning_threshold_reports_warning() {
        HealthReport report = EVALUATOR.evaluate(
                system(0.80, 1_000_000L, 500_000L),
                process(0.10),
                heap(100, 1000));

        assertEquals(HealthStatus.WARNING, report.status());
        assertTrue(report.alerts().stream()
                .anyMatch(a -> a.contains("System CPU is WARNING")));
        assertTrue(report.alerts().stream()
                .anyMatch(a -> a.contains("80.0%")));
    }

    @Test
    void value_exactly_at_critical_threshold_reports_critical() {
        HealthReport report = EVALUATOR.evaluate(
                system(0.95, 1_000_000L, 500_000L),
                process(0.10),
                heap(100, 1000));

        assertEquals(HealthStatus.CRITICAL, report.status());
        assertTrue(report.alerts().stream()
                .anyMatch(a -> a.contains("System CPU is CRITICAL")));
    }

    @Test
    void ram_closest_to_critical_wins_over_process_warning() {
        HealthReport report = EVALUATOR.evaluate(
                system(0.10, 1_000_000L, 10_000L),
                process(0.80),
                heap(100, 1000));

        assertEquals(HealthStatus.CRITICAL, report.status());
        assertTrue(report.alerts().stream()
                .anyMatch(a -> a.contains("System RAM is CRITICAL")));
        assertTrue(report.alerts().stream()
                .anyMatch(a -> a.contains("Target JVM CPU is WARNING")));
    }

    @Test
    void critical_is_never_downgraded_by_later_warning() {
        HealthReport report = EVALUATOR.evaluate(
                system(0.10, 1_000_000L, 10_000L),
                process(0.10),
                heap(100, 1000));

        assertEquals(HealthStatus.CRITICAL, report.status());
    }

    @Test
    void heap_thresholds_use_warning_at_80_and_critical_at_90() {
        HealthReport warning = EVALUATOR.evaluate(
                system(0.10, 1_000_000L, 500_000L),
                process(0.10),
                heap(800, 1000));

        assertEquals(HealthStatus.WARNING, warning.status());
        assertTrue(warning.alerts().stream()
                .anyMatch(a -> a.contains("JVM Heap is WARNING")));

        HealthReport critical = EVALUATOR.evaluate(
                system(0.10, 1_000_000L, 500_000L),
                process(0.10),
                heap(900, 1000));

        assertEquals(HealthStatus.CRITICAL, critical.status());
    }

    @Test
    void absent_system_metrics_skip_system_checks() {
        HealthReport report = EVALUATOR.evaluate(
                system(null, null, 500_000L),
                process(0.10),
                heap(100, 1000));

        assertEquals(HealthStatus.OK, report.status());
        assertEquals(List.of(), report.alerts());
    }

    @Test
    void zero_total_memory_disables_ram_check() {
        HealthReport report = EVALUATOR.evaluate(
                system(0.10, 0L, 1_000_000L),
                process(0.10),
                heap(100, 1000));

        assertEquals(HealthStatus.OK, report.status());
        assertEquals(List.of(), report.alerts());
    }

    @Test
    void zero_heap_max_disables_heap_check() {
        HealthReport report = EVALUATOR.evaluate(
                system(0.10, 1_000_000L, 500_000L),
                process(0.10),
                heap(900, 0));

        assertEquals(HealthStatus.OK, report.status());
        assertEquals(List.of(), report.alerts());
    }

    @Test
    void non_finite_or_negative_process_cpu_is_ignored() {
        HealthReport nan = EVALUATOR.evaluate(
                system(0.10, 1_000_000L, 500_000L),
                process(Double.NaN),
                heap(100, 1000));

        assertEquals(HealthStatus.OK, nan.status());

        HealthReport negative = EVALUATOR.evaluate(
                system(0.10, 1_000_000L, 500_000L),
                process(-1.0),
                heap(100, 1000));

        assertEquals(HealthStatus.OK, negative.status());
    }
}
