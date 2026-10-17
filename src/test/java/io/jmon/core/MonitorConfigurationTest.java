package io.jmon.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class MonitorConfigurationTest {

    @Test
    public void testDefaultConfiguration() {
        MonitorConfiguration config = MonitorConfiguration.builder().build();
        
        assertEquals(Duration.ofSeconds(3), config.getRefreshInterval());
        assertEquals(Optional.empty(), config.getSpringBootEndpoint());
        assertEquals(Optional.empty(), config.getSpringBootUsername());
        assertEquals(Optional.empty(), config.getSpringBootPassword());
    }

    @Test
    public void testCustomConfiguration() {
        String endpoint = "http://localhost:8080/actuator";
        String username = "user";
        String password = "password";
        
        MonitorConfiguration config = MonitorConfiguration.builder()
                .refreshInterval(Duration.ofSeconds(5))
                .springBootEndpoint(endpoint)
                .springBootUsername(username)
                .springBootPassword(password)
                .build();
        
        assertEquals(Duration.ofSeconds(5), config.getRefreshInterval());
        assertEquals(Optional.of(endpoint), config.getSpringBootEndpoint());
        assertEquals(Optional.of(username), config.getSpringBootUsername());
        assertEquals(Optional.of(password), config.getSpringBootPassword());
    }
}