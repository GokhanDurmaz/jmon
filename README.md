# JMon — Java Terminal Monitor

JMon is a terminal-based monitoring tool for Java developers. Inspired by `htop`, it provides real-time system resource monitoring, JVM metrics, and interactive process inspection from a single terminal interface.

JMon runs as a standalone CLI/TUI application. It does not need to be embedded into the Java applications it monitors.

## Features

- **System Monitoring:** Real-time CPU and memory utilization.
- **Java Process Monitoring:** Identify and monitor Java processes, including CPU and resident memory usage.
- **JVM Metrics:** JVM uptime, heap and non-heap memory, thread counts, loaded classes, and garbage collection statistics.
- **Memory Analysis:** Class histogram analysis and memory investigation tools.
- **Interactive Terminal UI:** Live refresh, keyboard navigation, and an `htop`-inspired interface.

## Installation

### macOS and Linux

JMon can be installed using the installation script. Java 17 or later is required.

```bash
curl -fsSL https://raw.githubusercontent.com/GokhanDurmaz/jmon/main/install.sh -o install.sh
bash install.sh
```

The installer downloads the latest published GitHub Release and installs JMon under:

- **Application:** `~/.local/opt/jmon`
- **Command:** `~/.local/bin/jmon`

If `~/.local/bin` is not already in your `PATH`, add it to your shell configuration.

For Zsh (the default shell on modern macOS):

```bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

Verify the installation:

```bash
jmon --help
```

> **Note:** The installation script downloads the latest published release. A successful GitHub Actions build alone does not guarantee that a release has been published.

### Updating JMon

Run the installation command again to install the latest published release:

```bash
bash install.sh
```

If you no longer have the installer script, download it again using the commands in the Installation section.

### Uninstalling

Remove the installed command and application files:

```bash
rm -f ~/.local/bin/jmon
rm -rf ~/.local/opt/jmon
```

This does not remove your source-code checkout or project files.

## Usage

### Monitor a Java process

```bash
jmon --pid 12345
```

Replace `12345` with the PID of the Java process you want to monitor.

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

Run JMon through Gradle:

```bash
./gradlew run --args="--pid 12345"
```

Alternatively, create a local application distribution:

```bash
./gradlew installDist
./build/install/jmon/bin/jmon --pid 12345
```

## Requirements

- Java 17 or later.
- macOS or Linux (primary target platforms).
- Appropriate operating-system permissions to inspect or attach to the target JVM. Depending on the environment, additional permissions may be required.

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

| Package | Responsibility |
|---|---|
| `io.jmon.app` | Application entry point, CLI arguments, and configuration |
| `io.jmon.core` | Domain models, metric snapshots, and health evaluation |
| `io.jmon.system` | System, process, and JVM metric collection |
| `io.jmon.tui` | Terminal UI, input handling, and view models |

## Roadmap and Limitations

- **Spring Boot Metrics:** Integration with Spring Boot Actuator and Micrometer endpoints.
- **Disk Monitoring:** Disk capacity and I/O metrics.
- **Remote JMX:** Current JVM metrics focus on local processes through the Attach API.
- **Windows Support:** Planned; macOS and Linux are the primary targets.

## Development

Development guidelines and project-specific agent instructions are documented in [`CLAUDE.md`](CLAUDE.md) and [`SKILL.md`](SKILL.md).

## License

See the repository's `LICENSE` file for licensing information.
