package io.jmon.infrastructure.update;

/**
 * Tiny child process used by the deferred-update integration tests:
 * it exits after the requested sleep so the applier can observe the
 * requesting JVM's exit through {@link ProcessHandle}.
 */
public final class SleeperProcess {

    private SleeperProcess() {
    }

    /**
     * Sleeps {@code args[0]} milliseconds and exits.
     *
     * @param args sleep duration in millis
     * @throws InterruptedException when interrupted
     */
    public static void main(String[] args)
            throws InterruptedException {
        Thread.sleep(Long.parseLong(args[0]));
    }
}
