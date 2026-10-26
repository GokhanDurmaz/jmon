package io.jmon.application;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Monitor configuration. */
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
/** Creates a new builder with the documented defaults. */

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
    /** Builder . */
    }

    /** Default refresh cadence in seconds for the monitoring loop. */
    private static final long DEFAULT_REFRESH_INTERVAL_SECONDS = 3;

    /** Default HTTP request timeout in seconds for Actuator calls. */
    private static final long DEFAULT_REQUEST_TIMEOUT_SECONDS = 10;

    /** Default HTTP connection timeout in seconds for Actuator calls. */
    private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;

    /** Default leak-observation window length in minutes. */
    private static final long DEFAULT_LEAK_OBSERVATION_WINDOW_MINUTES = 10;

    /** Mutable configuration assembly stage. */
    public static class Builder {
        private Duration refreshInterval = Duration.ofSeconds(
            DEFAULT_REFRESH_INTERVAL_SECONDS);
        private Optional<String> springBootEndpoint = Optional.empty();
        private Optional<String> springBootUsername = Optional.empty();
        private Optional<String> springBootPassword = Optional.empty();
        private Duration requestTimeout = Duration.ofSeconds(
            DEFAULT_REQUEST_TIMEOUT_SECONDS);
        private Duration connectTimeout = Duration.ofSeconds(
            DEFAULT_CONNECT_TIMEOUT_SECONDS);
        /** Sets the leak-observation window duration. */
        private Duration leakObservationWindow = Duration.ofMinutes(
            DEFAULT_LEAK_OBSERVATION_WINDOW_MINUTES);
        private Path diagnosticsDir = Path.of("diagnostics");

        /** Sets the leak-observation window duration. */
        public Builder leakObservationWindow(Duration window) {
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException(
                        "leak observation window must be positive");
            }
            /** Sets the directory where diagnostics artifacts are stored. */
            this.leakObservationWindow = window;
            return this;
        }
/** Sets the diagnostics artifact directory. */

        public Builder diagnosticsDir(Path diagnosticsDir) {
            if (diagnosticsDir == null
                    || diagnosticsDir.toString().isBlank()) {
                throw new IllegalArgumentException(
                        "diagnostics directory must not be blank");
            /** Sets the UI refresh interval. */
            }
            this.diagnosticsDir = diagnosticsDir;
            return this;
        }
/** Sets the Spring Boot Actuator base URL. */

        public Builder refreshInterval(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
            return this;
        /** Sets the Actuator basic-auth username. */
        /** Sets the Actuator base URL. */
        }

        /** Sets the Actuator base URL. */
        public Builder springBootEndpoint(String springBootEndpoint) {
            this.springBootEndpoint = Optional.of(springBootEndpoint);
            /** Sets the Actuator basic-auth password. */
            /** Sets the Actuator basic-auth username. */
            return this;
        }

        /** Sets the Actuator basic-auth username. */
        public Builder springBootUsername(String springBootUsername) {
            /** Sets the Actuator read timeout. */
            this.springBootUsername = Optional.of(springBootUsername);
            return this;
        }

        /** Sets the Actuator connection timeout. */
        public Builder springBootPassword(String springBootPassword) {
            this.springBootPassword = Optional.of(springBootPassword);
            return this;
        }
/** Builds an immutable configuration snapshot. */

        public Builder requestTimeout(Duration requestTimeout) {
            /** Sets the Actuator connection timeout. */
            this.requestTimeout = requestTimeout;
            return this;
        }

        /** Builds an immutable configuration snapshot. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /** Builds an immutable configuration snapshot. */
        public MonitorConfiguration build() {
            return new MonitorConfiguration(this);
        }
    }
}
