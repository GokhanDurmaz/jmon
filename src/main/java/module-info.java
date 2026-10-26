/* JMon layered module:
 * - io.jmon.domain.{memory,metrics,health,diagnostics} : pure domain models and rules
 * - io.jmon.application + monitoring / port.out        : use-case services and ports
 * - io.jmon.infrastructure.{jvm,system,diagnostics,actuator} : adapters
 *   (OSHI, Attach/JMX, jcmd, Actuator HTTP)
 * - io.jmon.presentation.tui(+view)                    : terminal UI
 * - io.jmon.app                                        : composition root
 * Dependency direction: presentation/application -> domain; infrastructure
 * implements application ports; io.jmon.app wires concrete implementations.
 */
module jmon {
    requires java.desktop;
    requires java.net.http;
    requires jdk.attach;
    requires java.management;
    requires jdk.management;

    requires com.github.oshi;
    requires com.sun.jna;
    requires com.sun.jna.platform;

    requires org.slf4j;
    requires org.tinylog.api;

    requires com.fasterxml.jackson.databind;
    requires org.jline;

    exports io.jmon.app;
    exports io.jmon.application;
    exports io.jmon.application.monitoring;
    exports io.jmon.application.port.out;
    exports io.jmon.domain.diagnostics;
    exports io.jmon.domain.health;
    exports io.jmon.domain.memory;
    exports io.jmon.domain.metrics;
    exports io.jmon.infrastructure.actuator;
    exports io.jmon.infrastructure.diagnostics;
    exports io.jmon.infrastructure.jvm;
    exports io.jmon.infrastructure.system;
    exports io.jmon.presentation.tui;
    exports io.jmon.presentation.tui.view;
}
