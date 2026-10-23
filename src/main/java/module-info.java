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
    exports io.jmon.core;
    exports io.jmon.system;
    exports io.jmon.spring;
    exports io.jmon.tui;
}
