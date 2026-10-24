package io.jmon.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public class MonitorConfiguration {
    private final Duration refreshInterval;
    private final Optional<String> springBootEndpoint;
    private final Optional<String> springBootUsername;
    private final Optional<String> springBootPassword;
    private final Duration requestTimeout;
    private final Duration connectTimeout;
    private final Duration leakObservationWindow;
    private final Path diagnosticsDir;

    private MonitorConfiguration(Builder builder) {
        this.refreshInterval = builder.refreshInterval;
        this.springBootEndpoint = builder.springBootEndpoint;
        this.springBootUsername = builder.springBootUsername;
        this.springBootPassword = builder.springBootPassword;
        this.requestTimeout = builder.requestTimeout;
        this.connectTimeout = builder.connectTimeout;
        this.leakObservationWindow = builder.leakObservationWindow;
        this.diagnosticsDir = builder.diagnosticsDir;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public Optional<String> getSpringBootEndpoint() {
        return springBootEndpoint;
    }

    public Optional<String> getSpringBootUsername() {
        return springBootUsername;
    }

    public Optional<String> getSpringBootPassword() {
        return springBootPassword;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getLeakObservationWindow() {
        return leakObservationWindow;
    }

    public Path getDiagnosticsDir() {
        return diagnosticsDir;
    }

    public static class Builder {
        private Duration refreshInterval = Duration.ofSeconds(3);
        private Optional<String> springBootEndpoint = Optional.empty();
        private Optional<String> springBootUsername = Optional.empty();
        private Optional<String> springBootPassword = Optional.empty();
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration leakObservationWindow = Duration.ofMinutes(10);
        private Path diagnosticsDir = Path.of("diagnostics");

        public Builder leakObservationWindow(Duration window) {
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException(
                        "leak observation window must be positive");
            }
            this.leakObservationWindow = window;
            return this;
        }

        public Builder diagnosticsDir(Path diagnosticsDir) {
            if (diagnosticsDir == null
                    || diagnosticsDir.toString().isBlank()) {
                throw new IllegalArgumentException(
                        "diagnostics directory must not be blank");
            }
            this.diagnosticsDir = diagnosticsDir;
            return this;
        }

        public Builder refreshInterval(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
            return this;
        }

        public Builder springBootEndpoint(String springBootEndpoint) {
            this.springBootEndpoint = Optional.of(springBootEndpoint);
            return this;
        }

        public Builder springBootUsername(String springBootUsername) {
            this.springBootUsername = Optional.of(springBootUsername);
            return this;
        }

        public Builder springBootPassword(String springBootPassword) {
            this.springBootPassword = Optional.of(springBootPassword);
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public MonitorConfiguration build() {
            return new MonitorConfiguration(this);
        }
    }
}
