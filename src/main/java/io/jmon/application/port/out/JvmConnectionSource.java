package io.jmon.application.port.out;

/**
 * Port for opening a bounded monitoring session against a running target
 * JVM (Attach/JMX lifecycle owned by the infrastructure adapter).
 *
 * Callers close the returned {@link JvmSession} once monitoring ends;
 * implementations must release their connection resources even when
 * session construction fails partway.
 */
public interface JvmConnectionSource {
    /** Connects to one PID and returns the JVM session. */
    JvmSession connect(int pid) throws Exception;
}
