package io.jmon.infrastructure.jvm;

import java.io.IOException;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.sun.tools.attach.VirtualMachine;

/** Jvm attach service. */
public class JvmAttachService {
/** Attaches to one PID and opens a JMX connector. */

    public JMXConnector connect(int pid) throws Exception {
        VirtualMachine vm = null;

        try {
            vm = VirtualMachine.attach(Integer.toString(pid));

            String address = vm.startLocalManagementAgent();

            if (address == null || address.isBlank()) {
                throw new IOException(
                        "Target JVM did not provide a local JMX address"
                );
            }

            return JMXConnectorFactory.connect(
                    new JMXServiceURL(address)
            );
        } finally {
            if (vm != null) {
                vm.detach();
            }
        }
    }
}
