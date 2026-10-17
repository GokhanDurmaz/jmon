CLAUDE.md — JMon Project Guide
1. Project Mission
Build JMon, a terminal-based monitoring tool inspired by htop, designed especially for Java developers working with Java and Spring Boot applications.
JMon should make it easy to inspect operating-system resource usage and understand the health and behavior of Java applications from a single interactive terminal interface.
The initial target platforms are macOS and Linux. Keep platform-specific behavior behind clear abstractions so Windows support can be considered later without redesigning the application.
JMon is a standalone CLI/TUI application. It must not require users to embed JMon into the Spring Boot applications they monitor.
2. Product Goals
2.1 System Monitoring
* Overall CPU utilization and, where supported, per-core utilization.
* Physical memory, available memory, swap, and usage percentages.
* Process listing with PID, name/command, CPU, memory/RSS, and user where available.
* Sorting and filtering processes.
* Disk capacity and basic disk I/O metrics where reliably supported.
* Configurable refresh interval.
2.2 Java Process Monitoring
* Discover Java processes and allow the user to select one.
* Show process CPU and memory alongside JVM-specific information when available.
* Display JVM uptime, heap and non-heap usage, thread counts, loaded classes, and garbage-collection statistics when the selected data source supports them.
* Clearly distinguish unavailable data from a real zero value.
2.3 Spring Boot Monitoring
* Connect to an explicitly configured Spring Boot Actuator/Micrometer endpoint.
* Support configurable base URL, authentication, connection/read timeouts, and TLS verification.
* Display available JVM, process, HTTP server, and connection-pool metrics when exposed by the application.
* Treat metric names and availability as version- and configuration-dependent.
* Never assume Actuator is enabled or publicly accessible.
2.4 Terminal User Interface
* Responsive panels for system, processes, JVM, and Spring Boot metrics.
* Keyboard navigation, selection, sorting, and a clear quit action.
* Live refresh without blocking the UI thread.
* Readable output in small terminals and support for reduced-color terminals.
* Useful loading, disconnected, unsupported, and error states.
2.5 Reliability
* Predictable behavior when permissions are limited or a metric is unavailable.
* Bounded resource usage and clean shutdown.
* No collection of telemetry outside the local machine unless a user explicitly configures a remote endpoint.
* Safe handling of credentials and sensitive endpoint information.
3. Non-Goals for the Initial MVP
Do not implement these unless explicitly requested:
* A web dashboard.
* A metrics database or long-term time-series storage.
* Distributed tracing or a full observability platform.
* Automatic process termination or destructive system actions.
* Automatic discovery or scanning of arbitrary remote hosts.
* AI-generated diagnoses or autonomous remediation.
* Windows-specific support before the core macOS/Linux experience is stable.
4. Architecture Principles
Use a layered, modular architecture. Keep terminal rendering, metric collection, and domain models separate.
Suggested logical components:
* app: startup, CLI entry point, configuration, dependency wiring, and shutdown.
* core: metric models, collector interfaces, service contracts, and shared errors.
* system: OSHI-backed CPU, memory, disk, and process collection.
* jvm: Java process discovery and supported JVM data sources.
* spring: Actuator/Micrometer HTTP client and metric mapping.
* tui: terminal screens, navigation, key bindings, formatting, and view models.
* config: configuration parsing, validation, defaults, and secret handling.
* test-support: reusable fixtures or fake collectors when needed.
These are logical boundaries, not a requirement to create a separate Gradle/Maven module for every component. Prefer a simple package structure first. Split into build modules only when there is a concrete benefit.
Dependency Direction
* The UI depends on domain/service interfaces, not concrete collectors.
* Collectors implement interfaces defined at an appropriate boundary.
* Domain models must not depend on the terminal UI library, OSHI, HTTP client implementation, or Spring Boot.
* The Spring Boot application being monitored is an external target. JMon itself does not need to be a Spring Boot application.
* Avoid cyclic dependencies.
* Keep platform-specific APIs behind interfaces and document platform limitations.
Suggested Interfaces
Treat these as concepts, not mandatory names:
* SystemMetricsCollector
* ProcessCollector
* JavaProcessDiscovery
* JvmMetricsProvider
* SpringBootMetricsClient
* RefreshScheduler
* MonitorConfiguration
Prefer immutable snapshots and explicit timestamps. Represent unavailable or unsupported values explicitly instead of silently converting them to zero.
5. Technology Guidance
Choose versions compatible with the repository's existing Java version and build tool. Before adding or upgrading dependencies, inspect the current build files and verify compatibility.
Potential choices:
* Java: Use the repository's configured JDK. Prefer a currently supported LTS version if the project is new.
* Build: Preserve the existing Gradle or Maven setup.
* Terminal UI: Evaluate Lanterna or another maintained Java TUI library.
* System information: Evaluate OSHI for cross-platform CPU, memory, disk, and process data.
* Spring metrics: Use Spring Boot Actuator/Micrometer endpoints over HTTP where configured.
* JVM metrics: Use the most reliable supported mechanism for the required metric. Consider Attach API or JMX only when justified, and document permissions and operational constraints.
* Testing: Use the repository's existing test framework. Otherwise, choose a conventional Java unit-testing setup.
Do not add a dependency solely because it is listed here. Confirm that it is maintained, compatible, and needed. Do not guess library APIs: inspect dependency documentation, source, or existing examples when uncertain.
6. Data Collection and Performance Rules
* Keep collection asynchronous or off the UI rendering thread.
* Avoid overlapping collection cycles when a previous cycle has not completed.
* Use bounded executors and closeable resources.
* Reuse clients where appropriate and configure connection/read timeouts.
* Do not make a network request for every individual metric if one endpoint response can provide the required data efficiently.
* Distinguish collection time from display time.
* Keep timestamps and units explicit.
* Calculate CPU deltas using the collector's required sampling model. Do not present an initial sample as a valid percentage if it is not.
* Avoid excessive polling. Make refresh frequency configurable and use conservative defaults.
* Handle process exit, connection loss, permission denial, malformed responses, and missing metrics.
* Do not log secrets, authorization headers, or sensitive endpoint URLs.
* Do not claim metrics are accurate on a platform unless implementation and tests support that claim.
7. Security and Privacy
* Bind no network listener by default. JMon should be a local terminal application unless a future feature explicitly requires a server.
* Treat remote Actuator endpoints as untrusted inputs.
* Use TLS certificate verification by default; never disable it silently.
* Do not hard-code credentials or commit secrets.
* Prefer environment variables or a local configuration mechanism with appropriate file permissions for credentials.
* Avoid printing tokens in error messages or debug logs.
* Do not expose sensitive Actuator endpoints just to make JMon work. Explain the minimum required endpoint configuration.
* Do not invoke shell commands with concatenated, untrusted input. Prefer library APIs or safe process invocation with separate arguments.
* Do not terminate or modify monitored processes unless a separately specified feature explicitly requires it and includes clear confirmation behavior.
8. User Experience
* Keep the default screen useful immediately after launch.
* Make units explicit: %, MiB/GiB, milliseconds, requests/second, and so on.
* Show the selected process and the current collection/connection status.
* Use stable sorting and preserve selection when data refreshes if possible.
* Handle narrow terminal sizes gracefully.
* Provide keyboard help in the UI and document shortcuts in the README.
* Use color to communicate status, but never rely on color alone.
* Avoid flicker by using the TUI framework's recommended rendering approach.
* Provide useful errors with a next step, not just stack traces.
* Keep detailed diagnostics available through an opt-in debug mode.
9. Engineering Workflow for the Coding Agent
At the start of each task:
1. Read this file and SKILL.md if present.
2. Inspect the repository structure, README, build files, current implementation, tests, and Git status.
3. Read PROJECT_STATUS.md if it exists. Treat actual source code and build results as authoritative if status notes are stale.
4. Identify the smallest useful implementation step and the files it will affect.
5. State assumptions if a decision materially affects architecture; otherwise use a sensible default and continue.
6. Avoid asking the user to repeat information already present in repository documentation.
During implementation:
* Follow existing conventions unless they are clearly harmful.
* Make incremental, coherent changes.
* Write or update tests for behavior changes.
* Keep functions and classes focused.
* Prefer clear names and straightforward control flow over speculative abstractions.
* Do not rewrite unrelated code or reformat the entire repository.
* Do not delete user work or overwrite files without understanding their purpose.
* Do not claim a command passed unless it was actually run and its result was checked.
* If a command cannot be run, state why and provide the exact remaining verification step.
* When an API or dependency is uncertain, verify it rather than inventing methods.
* Avoid leaving the project in a broken intermediate state.
Before finishing:
1. Run relevant unit tests and build checks.
2. Run formatting/static checks if configured and relevant.
3. Review git diff for accidental changes, secrets, generated files, and unrelated edits.
4. Update documentation when behavior, configuration, or keyboard controls change.
5. Update PROJECT_STATUS.md if the repository uses it, recording completed work, current state, verification, known issues, and the next concrete task.
6. Report a concise summary, files changed, commands/tests run, results, and remaining limitations.
10. Definition of Done
A task is complete only when:
* The requested behavior is implemented, or any blocker is clearly explained.
* The project compiles for the affected code path when the environment permits.
* Relevant tests are added or updated and run where possible.
* Unsupported platform behavior is handled or documented.
* Error and unavailable-data states are considered.
* Documentation and project status are updated when needed.
* The final report accurately describes what was and was not verified.
11. Initial MVP Roadmap
Work in small, verifiable increments. Do not implement the whole roadmap in one change.
Phase 0 — Repository Baseline
* Inspect repository, Java version, build system, dependencies, and existing conventions.
* Ensure the project builds and tests run.
* Document architectural decisions and a minimal execution plan.
Phase 1 — Runnable Terminal Shell
* Create the CLI entry point and basic TUI.
* Display a title/header, refresh indicator, keyboard help, and clean quit behavior.
* Add a fake-data mode or fake collector so UI behavior can be tested deterministically.
Phase 2 — System Metrics
* Implement CPU and memory snapshots using OSHI or a verified equivalent.
* Add process listing, selection, and sorting.
* Add safe refresh scheduling and handle missing/permission-restricted data.
* Validate on macOS and Linux where environments are available.
Phase 3 — Java Process Support
* Identify Java processes and show their OS-level CPU/memory data.
* Add one supported JVM metrics mechanism with clear capability reporting.
* Document permissions and limitations.
Phase 4 — Spring Boot Metrics
* Add configurable Actuator endpoint support.
* Implement timeouts, authentication configuration, and graceful connection states.
* Display only metrics actually available from the target application.
* Add mock HTTP tests for valid, missing, malformed, and unauthorized responses.
Phase 5 — Polish
* Improve responsive layout, help, configuration, logging, packaging, and documentation.
* Add regression tests and verify the supported platforms.
* Revisit roadmap priorities based on actual use.
12. Project-Specific Decision Rules
When requirements are ambiguous:
1. Prefer a small, working, testable feature over a large speculative framework.
2. Prefer local read-only monitoring over privileged operations.
3. Prefer standard, documented interfaces over runtime tricks.
4. Prefer explicit capability reporting over pretending every metric exists everywhere.
5. Preserve compatibility with the project's current Java/build configuration unless a deliberate migration is approved.
6. Ask a concise question only when a decision is genuinely blocking or has costly consequences; otherwise state the assumption and proceed.

