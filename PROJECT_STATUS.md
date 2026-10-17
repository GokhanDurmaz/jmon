# JMon Project Status

## Project Summary
JMON is an early-stage terminal monitor for Java/JVM processes and system resources. It provides real-time metrics via a terminal-based UI.

## Implemented Components
- `io.jmon.app`: CLI entry point (`Main.java`) and basic configuration.
- `io.jmon.core`: Domain models for `SystemMetrics`, `ProcessMetrics`, `JvmMetrics`, `MonitorConfiguration`, `HealthReport`, and `HealthStatus`.
- `io.jmon.system`:
    - `RealSystemMetricsCollector`: CPU and memory metrics via OSHI.
    - `TargetProcessCollector`: Target process information via OSHI.
    - `JvmMetricsCollector`: JVM metrics via JMX/Attach API.
    - `JvmGcMonitor`: GC monitoring.
    - `ClassHistogramCollector`: Class memory statistics.
    - `MemoryLeakDetector`: Memory leak detection logic.
- `io.jmon.tui`: `Tui.java` for interactive rendering and input handling using JLine.

## Implemented Features
- `Implemented`: System metrics (CPU, RAM).
- `Implemented`: Target process selection and monitoring (CPU, RSS).
- `Implemented`: JVM metrics (Heap, Non-heap, Threads, Classes, GC).
- `Implemented`: Class histogram and memory leak detection.
- `Implemented`: Real-time TUI with keyboard navigation.
- `Planned`: Spring Boot Actuator/Micrometer metrics.
- `Planned`: Disk capacity and I/O metrics.

## Architecture and Data Flow
- The `Tui` class orchestrates the application, using collectors from `io.jmon.system` to populate domain models from `io.jmon.core`.
- `Main` handles CLI arguments and initializes the `Tui`.

## Build and Test Status
- Build command: `./gradlew build`
- Test command: `./gradlew test`
- Build and tests were not executed during this task.

## Known Limitations
- `com.sun.tools.attach` dependency might be missing in some JDK distributions (e.g., some OpenJDK builds).
- Remote JMX metrics currently limited to local MBeans (due to `ManagementFactory` usage in `JvmMetricsCollector`).
- Spring Boot integration not yet implemented.

## Current Development State
- Core monitoring for system and target JVMs is functional.
- TUI is operational.
- Next priority: Spring Boot Actuator integration.

## Guidance for Future Coding Agents
- Use `io.jmon.system` for all new metric collection logic.
- Keep domain models in `io.jmon.core`.
- Use `Tui` for all UI-related updates.
- Ensure all new collectors handle missing/unavailable data gracefully.
- Read `CLAUDE.md` and `SKILL.md` before making implementation changes.
