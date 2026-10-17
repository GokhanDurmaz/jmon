# JMon - Java Terminal Monitor

JMon is a terminal-based monitoring tool designed for Java developers to inspect operating-system resource usage and understand the health and behavior of Java applications from a single interactive terminal interface. It is inspired by `htop`.

## Project Mission
JMon makes it easy to monitor system resources and JVM-specific metrics (like heap usage, thread counts, and GC statistics) in real-time. It is designed to be a standalone CLI/TUI application that does not require embedding into the applications it monitors.

## Features
- **System Monitoring**: Real-time CPU and memory utilization metrics.
- **Java Process Monitoring**: Identify and select specific Java processes to monitor their CPU and resident memory usage.
- **JVM Metrics**: Display JVM uptime, heap/non-heap usage, thread counts, loaded classes, and garbage collection statistics (via JMX/Attach API).
- **Memory Analysis**: Real-time class histogram (top classes by shallow heap) and memory leak detection.
- **Terminal User Interface**: A responsive, interactive TUI with keyboard navigation and live refresh.

## Technology Stack
- **Java**: 17+
- **Build System**: Gradle
- **System Info**: OSHI (Operating System and Hardware Information)
- **Terminal UI**: JLine
- **Native Access**: JNA
- **Logging**: TinyLog, SLF4J
- **HTTP Client**: Apache HttpClient (planned for Spring Boot metrics)

## Prerequisites
- Java 17 or later.
- Appropriate OS-level permissions to attach to JVM processes (may require root/admin depending on the environment).

## Build Instructions
To build the project:
```bash
./gradlew build
```

## Running JMON
To run the application via Gradle:
```bash
./gradlew run --args="--pid <java-process-id>"
```
Example:
```bash
./gradlew run --args="--pid 12345"
```

## Project Structure
- `io.jmon.app`: Application entry point, CLI argument parsing, and configuration.
- `io.jmon.core`: Core domain models, metric snapshots, and health evaluation logic.
- `io.jmon.system`: Metric collection logic for OS resources, target processes, and JVM internals.
- `io.jmon.tui`: Terminal User Interface rendering, input handling, and view models.

## Limitations and Roadmap
- **Spring Boot Metrics**: Planned integration with Spring Boot Actuator/Micrometer endpoints.
- **Disk Monitoring**: Planned support for disk capacity and I/O metrics.
- **Remote JMX**: Currently, JVM metrics are collected from local processes via the Attach API.
- **Platform Compatibility**: Focus is on macOS and Linux; Windows support is planned.

## Development
Follow the guidelines in `CLAUDE.md` and `SKILL.md` for development practices.
