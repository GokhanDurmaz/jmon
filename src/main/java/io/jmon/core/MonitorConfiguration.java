package io.jmon.core;

import java.time.Duration;
import java.util.Optional;

public class MonitorConfiguration {
    private final Duration refreshInterval;
    private final Optional<String> springBootEndpoint;
    private final Optional<String> springBootUsername;
    private final Optional<String> springBootPassword;

    private MonitorConfiguration(Builder builder) {
        this.refreshInterval = builder.refreshInterval;
        this.springBootEndpoint = builder.springBootEndpoint;
        this.springBootUsername = builder.springBootUsername;
        this.springBootPassword = builder.springBootPassword;
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

    public static class Builder {
        private Duration refreshInterval = Duration.ofSeconds(3);
        private Optional<String> springBootEndpoint = Optional.empty();
        private Optional<String> springBootUsername = Optional.empty();
        private Optional<String> springBootPassword = Optional.empty();

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

        public MonitorConfiguration build() {
            return new MonitorConfiguration(this);
        }
    }
}
