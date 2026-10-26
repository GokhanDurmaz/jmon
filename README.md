# JMon — Java Terminal Monitor

JMon is a terminal-based monitoring tool for Java developers. Inspired by `htop`, it provides real-time system resource monitoring, JVM metrics, and interactive process inspection from a single terminal interface.

JMon runs as a standalone CLI/TUI application. It does not need to be embedded into the Java applications it monitors.

## JMon Terminal UI

![JMon Terminal Interface](screenshots/jmon_dashboard.png)

## Features

- **System Monitoring:** Real-time CPU and memory utilization.
- **Java Process Monitoring:** Identify and monitor Java processes, including CPU and resident memory usage.
- **JVM Metrics:** JVM uptime, heap and non-heap memory, thread counts, loaded classes, and garbage collection statistics.
- **Memory Analysis:** Class histogram analysis and memory investigation tools.
- **Interactive Terminal UI:** Live refresh, keyboard navigation, and an `htop`-inspired interface.
- **Version Information:** Display the installed version with `jmon --version`.

## Installation

### Requirements

- Java 17 or later.
- A supported terminal environment.
- Appropriate operating-system permissions to inspect or attach to the target JVM.

### macOS and Linux

JMon can be installed using the installation script.

```bash
curl -fsSL https://raw.githubusercontent.com/GokhanDurmaz/jmon/main/install.sh -o install.sh
bash install.sh
```

The installer downloads the latest published GitHub Release and installs JMon under your user directory.

Default locations:

- **Application:** `~/.local/opt/jmon`
- **Command:** `~/.local/bin/jmon`

If `~/.local/bin` is not already in your `PATH`, add it to your shell configuration.

For Zsh (the default shell on modern macOS):

```bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### Windows (CMD)

JMon can be installed using the Windows batch installer. Java 17 or later is required.

1. Download `install-windows.bat` from the repository.
2. Open **Command Prompt (CMD)**.
3. Navigate to the directory where you downloaded the file.
4. Run the installer:

```cmd
curl.exe -fL https://raw.githubusercontent.com/GokhanDurmaz/jmon/main/install-windows.bat -o "%USERPROFILE%\Downloads\install-windows.bat"
"%USERPROFILE%\Downloads\install-windows.bat"
```

The installer downloads the latest published GitHub Release, extracts JMon to your user directory, and adds the application's `bin` directory to your user `PATH`.

The default installation directory is:

```text
%USERPROFILE%\.local\opt\jmon
```

Close CMD and open a new window after installation so that the updated `PATH` takes effect.

Verify the installation:

```cmd
java -version
jmon --version
jmon --help
```

**Note:** Java 17 or later must be available in your terminal's `PATH`. If Java 8 is selected instead, update your `JAVA_HOME` and `PATH` to point to your Java 17+ installation.

The Windows installer requires a published GitHub Release containing `jmon-windows.zip`. A successful GitHub Actions build alone does not guarantee that a release has been published.

## Updating JMon

To update JMon, run the installation command again for your platform. The installer downloads the latest published release.

For macOS and Linux, run:

```bash
bash install.sh
```

For Windows, download the latest `install-windows.bat` from the repository and run it again from CMD.

## Uninstalling

### macOS and Linux

```bash
rm -f ~/.local/bin/jmon
rm -rf ~/.local/opt/jmon
```

### Windows (CMD)

```cmd
rmdir /S /Q "%USERPROFILE%\.local\opt\jmon"
```

You can also remove `%USERPROFILE%\.local\opt\jmon\bin` from your user `PATH` if you no longer use JMon.

Uninstalling does not remove your source-code checkout or project files.

## Usage

### Monitor a Java process

```bash
jmon --pid 12345
```

Replace `12345` with the PID of the Java process you want to monitor.

On Windows, use the same JMon arguments from CMD:

```cmd
jmon --pid 12345
```

To list Java processes, you can use:

```bash
jps -l
```

`jps` is included with the JDK.

### Monitor a Spring Boot application through Actuator

Spring Boot Actuator monitoring is optional. Pass an Actuator metrics
endpoint and, if required, the HTTP basic auth credentials:

```bash
jmon --pid 12345 --spring-endpoint http://localhost:8080
jmon --pid 12345 --spring-endpoint http://localhost:8080 --spring-user actuator --spring-password actuator
```

Notes:

- The application must expose `GET /actuator/metrics`; no other sensitive
  Actuator endpoint is required.
- JVM metrics matching metrics such as `jvm.memory.used` are collected only
  if they are exposed by the target application.
- Metric values are requested without tag filters, so Spring Boot aggregates
  them across every tag dimension. For example, `jvm.memory.used` combines
  the `heap` and non-heap memory areas. Tag-based breakdowns are a possible
  future feature.
- If authentication fails or the endpoint is unavailable, the Spring Boot
  panel shows the connection status while local JVM monitoring continues
  to work.

### Memory leak analysis

Tuning is optional; sane defaults are used otherwise:

```bash
jmon --pid 12345 --leak-window-minutes 30
jmon --pid 12345 --diagnostics-dir /var/tmp/jmon-dumps
```

JMon continuously tracks the monitored JVM's memory behavior and classifies
it into `INSUFFICIENT DATA`, `NORMAL`, `GROWTH OBSERVED`,
`POSSIBLE MEMORY LEAK`, and `COLLECTION DEGRADED` states. The analysis
combines:

- the post-GC heap floor (real values from GC notifications, timestamped at
  drain time, so they may lag the GC event by up to one refresh cycle),
- GC pressure (collections per minute and time share from JMX counters,
  computed only over segments without a counter reset),
- per-class instance and shallow-heap growth keyed by the **verbatim**
  histogram class name (never a decoded display label); classes missing from
  a later histogram are treated as missing data, not zero.

The panel shows the classification, the observation window, valid sample
counts, the post-GC trend, GC pressure, and up to three leading growing
classes. `e` opens a detail view with the full explanation and all leading
classes; `r` resets the analysis baseline explicitly.

Defaults (all deterministic and documented in the source): the observation
window is 10 minutes (`--leak-window-minutes`, 1-1440), a suspicion requires
at least 6 valid heap samples and a post-GC trend over at least 4 GC events
spanning 60 seconds, and the risk threshold is a post-GC slope of at least
max(512 KiB/min, 0.1% of heap per minute) **corroborated** by a sustained
class growth of at least 1 MiB or an especially steep slope (2x threshold).
Hysteresis keeps the flagged state until three consecutive evaluations show
a slope below half the threshold. Repeated events are deduplicated and
recovery is recorded.

**Important:** this is a trend detector that identifies suspicious growth.
It does not prove a permanent leak; retained objects may be legitimate
lifecycles. Use it as evidence for further investigation.

### Deep diagnostics (heap dumps and JFR)

The `analyze`/leak view never starts anything automatically. Press `d` to
open the deep diagnostics menu, which requires explicit confirmation:

| Key | Action                                                        |
|-----|----------------------------------------------------------------|
| `y` | Generate a heap dump via `jcmd GC.heap_dump`                    |
| `j` | Start a bounded JFR recording (120 s, `settings=profile`)      |
| `s` | Stop the active JFR recording early (partial file)             |
| `x` / any key | Close the menu; `x` also cancels an active operation    |

The menu shows the target PID, the output directory
(`--diagnostics-dir`, default `./diagnostics`), and the estimated disk use
(about the committed heap size). Heap dumps can pause the target JVM and
consume substantial disk space and are refused if the filestore looks too
small or the destination path already exists; dumps are never overwritten.
Timeouts are enforced (90 s for dumps, 20 s for JFR commands) and timed-out
or cancelled operations are reported with any partial artifact.

Known limitations, reported honestly by JMon:

- Retained-size/dominator analysis is **not** bundled: it requires an HPROF
  parser or external analysis tooling (such as Eclipse MAT or VisualVM) for
  the generated dump. The dump is an artifact for external analysis.
- The `jcmd` binary of the JDK running JMon (or on PATH) is used; actual
  per-target support for `GC.heap_dump`/`JFR` is validated on execution.
- Class histogram collection (`jcmd GC.class_histogram -all`) is relatively
  expensive on the target JVM; JMon runs it at a fixed 15 s cadence with a
  20 s timeout and clearly shows degraded states when it fails.

### Keyboard controls

| Key       | Action                                                    |
|-----------|-----------------------------------------------------------|
| `q` / `Q` | Quit JMon                                                  |
| `h` / `?` | Show or hide the help overlay (monitoring keeps running)   |
| `+` / `=` | Increase the refresh interval by 1 second                  |
| `-` / `_` | Decrease the refresh interval by 1 second                  |
| `j` / Down | Select the next row in OBJECT & CLASS MEMORY              |
| `k` / Up   | Select the previous row in OBJECT & CLASS MEMORY          |
| `ENTER` / `o` | Open the detail view of the selected class entry        |
| `b` / `B`  | Return from a detail view to the dashboard                 |
| `e`        | Toggle the memory-leak analysis detail view                |
| `d`        | Open the deep diagnostics confirmation menu                |
| `r`        | Reset the memory analysis baseline                         |

The class detail view shows the histogram name verbatim (never rewritten or
guessed), the decoded array and component types for array classes, module
information when the JVM histogram provides it, instance counts, total and
average **shallow heap**, and an explicit warning that shallow heap excludes
referenced and retained objects. Only classes from named `java.*`/`jdk.*`
modules are labeled as JDK classes; other entries remain unclassified because
the histogram does not provide reliable identity information for them.

The refresh interval is clamped between 1000 and 60000 ms. The same interval
drives the Spring Boot Actuator polling when an endpoint is configured.
A help overlay listing these controls is always available with `h` or `?`.

### Display version

```bash
jmon --version
```

The short form is also supported:

```bash
jmon -v
```

### Display help

```bash
jmon --help
```

### Run from the source code

Clone the repository and build the application using Gradle:

```bash
git clone https://github.com/GokhanDurmaz/jmon.git
cd jmon
./gradlew build
```

On Windows, use the Gradle wrapper batch file:

```cmd
gradlew.bat build
```

Run JMon through Gradle on macOS or Linux:

```bash
./gradlew run --args="--pid 12345"
```

On Windows:

```cmd
gradlew.bat run --args="--pid 12345"
```

Alternatively, create a local application distribution:

```bash
./gradlew installDist
./build/install/jmon/bin/jmon --pid 12345
```

On Windows:

```cmd
gradlew.bat installDist
build\install\jmon\bin\jmon.bat --pid 12345
```

## Requirements and Platform Support

- Java 17 or later.
- macOS and Linux are primary target platforms.
- Windows distribution and installation are provided; some monitoring capabilities may differ by operating system.
- Appropriate operating-system permissions may be required to inspect or attach to the target JVM.

## Technology Stack

| Technology | Purpose |
|---|---|
| Java 17+ | Application runtime |
| Gradle | Build and distribution |
| OSHI | Operating-system and hardware metrics |
| JLine | Terminal interaction and UI support |
| JNA | Native operating-system access |
| Tinylog / SLF4J | Logging |
| Apache HttpClient | HTTP integration; Spring Boot metrics integration is planned |

## Project Structure

JMon is organized as a layered architecture with an explicit dependency
direction: `presentation -> application -> domain`, with
`infrastructure` implementing application-owned ports behind
domain/framework boundaries and `app` acting as the composition root
that wires concrete implementations into the ports.

| Package | Layer | Responsibility |
|---|---|---|
| `io.jmon.domain.{memory,metrics,health,diagnostics}` | Domain | Pure monitoring concepts, value objects, and domain rules: memory-leak analysis (`MemoryTrendAnalyzer`, `ClassGrowthTracker`, `ClassIdentity`), metric snapshots (`SystemMetrics`, `ProcessMetrics`, `JvmMetrics`, Spring Boot snapshots), health evaluation (`HealthEvaluator`), and deep-diagnostics outcomes (`DiagnosticOutcome`, `OperationCapability`). No OSHI/JMX/HTTP/JLine dependencies. |
| `io.jmon.application` (+ `.monitoring`, `.port.out`) | Business/Application | Use cases (`SpringBootMetricsPoller`, legacy `JvmMonitoringService` aggregation APIs under `.monitoring`; `MemoryTrendAnalyzer` lives in the domain) and outbound ports under `.port.out` (`SystemMetricsCollector`, `ProcessCollector`, `TargetProcessCollector`, `ClassHistogramCollector`, `JvmConnectionSource`/`JvmSession`, `SpringBootMetricsClient`, `DiagnosticsService`). `MonitorConfiguration` (user config) stays at the `io.jmon.application` root. |
| `io.jmon.infrastructure.{system,jvm,diagnostics,actuator}` | Data/Infrastructure | Adapters implementing the application ports, grouped by capability: OSHI system/process collectors (`.system`), JVM Attach/JMX session and metrics (`.jvm`: `JvmAttachService`, `AttachJvmConnectionSource`, `JmxJvmSession`, `JvmMetricsCollector`, `JmxGcMonitor`), `jcmd` class histograms and deep diagnostics (`.diagnostics`), and the HTTP Actuator client (`.actuator`). |
| `io.jmon.presentation.tui` (+ `.view`) | Presentation | Terminal UI and keyboard handling (`Tui`) with rendering isolated in `presentation.tui.view` (`TuiRenderer`). Uses only application ports and domain models — never concrete infrastructure services. |
| `io.jmon.app` | Composition root | CLI argument parsing and wiring concrete infrastructure into application ports (`Main`, `TestApp`). |

The boundaries are enforced by ArchUnit tests in
`src/test/java/io/jmon/app/ArchitectureTest.java`, which fail the build
if the domain depends on frameworks or other layers, if the application
depends on infrastructure or the presentation, if the presentation
depends on infrastructure (OSHI/JMX/HTTP), if infrastructure reaches
into use-case services, the UI, or the composition root, or if anything
but `io.jmon.app` (and infrastructure internally) references concrete
adapters.

## Roadmap and Limitations

- **Spring Boot Metrics:** Integration with Spring Boot Actuator and Micrometer endpoints.
- **Disk Monitoring:** Disk capacity and I/O metrics.
- **Remote JMX:** Current JVM metrics focus on local processes through the Attach API.
- **Cross-platform Improvements:** Further testing and refinement of Windows-specific monitoring behavior.

## Development

Development guidelines and project-specific agent instructions are documented in [`CLAUDE.md`](CLAUDE.md) and [`SKILL.md`](SKILL.md).

### Static analysis

JMon uses Gradle's built-in Checkstyle and PMD plugins with small, hand-picked correctness rulesets (no full formatting suites).

- Config: [`config/checkstyle/checkstyle.xml`](config/checkstyle/checkstyle.xml) (plus [`config/checkstyle/suppressions.xml`](config/checkstyle/suppressions.xml) for targeted, justified suppressions) and [`config/pmd/pmd-ruleset.xml`](config/pmd/pmd-ruleset.xml).
- Commands: `./gradlew checkstyleMain checkstyleTest pmdMain pmdTest` (or just `./gradlew check`, which includes them).
- Policy: findings on pre-existing code are documented rather than silently refactored; suppressions are per-file/per-line with a written justification, never global. `module-info.java` is excluded because Checkstyle cannot parse JPMS module declarations.
- Tool versions: Checkstyle 10.21.4, PMD 7.9.0 (see `build.gradle`).

## License

See the repository's `LICENSE` file for licensing information.