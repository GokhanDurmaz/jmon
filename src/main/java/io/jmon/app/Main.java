package io.jmon.app;

import io.jmon.core.MonitorConfiguration;
import io.jmon.tui.Tui;

public class Main {

    public static void main(String[] args) {
        Integer pid = parsePid(args);

        if (pid == null || pid <= 0) {
            System.err.println("Usage: jmon --pid <java-process-id>");
            System.err.println("Example: jmon --pid 12345");
            System.exit(2);
            return;
        }

        MonitorConfiguration config =
                MonitorConfiguration.builder().build();

        new Tui(config, pid).start();
    }

    private static Integer parsePid(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--pid".equals(args[i]) && i + 1 < args.length) {
                try {
                    return Integer.parseInt(args[i + 1]);
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
