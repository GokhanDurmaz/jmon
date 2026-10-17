module jmon {
    requires java.desktop;
    requires jdk.attach;
    requires java.management;
    requires jdk.management;

    requires com.github.oshi;
    requires com.sun.jna;
    requires com.sun.jna.platform;

    requires org.apache.httpcomponents.httpclient;
    requires org.apache.httpcomponents.httpcore;

    requires org.slf4j;
    requires org.tinylog.api;

    exports io.jmon.app;
    exports io.jmon.core;
    exports io.jmon.system;
    exports io.jmon.tui;
}
