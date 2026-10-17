package io.jmon.system;

import com.sun.tools.attach.VirtualMachine;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;

public class JvmAttachService {

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
