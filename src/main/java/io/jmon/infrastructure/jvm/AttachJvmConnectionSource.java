package io.jmon.infrastructure.jvm;

import io.jmon.application.port.out.JvmConnectionSource;
import io.jmon.application.port.out.JvmSession;

/**
 * {@link JvmConnectionSource} implementation based on the JDK Attach API
 * and the target JVM's local management agent. Session construction
 * failures (heap-pool discovery, missing GC MXBeans, connector
 * rejection) are translated by closing any partial connection resources
 * before propagating.
 */
public final class AttachJvmConnectionSource implements JvmConnectionSource {

    private final JvmAttachService attachService;

    /** Creates the default attach-based connection source. */
    public AttachJvmConnectionSource() {
        this(new JvmAttachService());
    }

    AttachJvmConnectionSource(JvmAttachService attachService) {
        this.attachService = attachService;
    }

    @Override
    public JvmSession connect(int pid) throws Exception {
        return new JmxJvmSession(attachService.connect(pid));
    }
}
