package com.dach.sagiri.health;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.schedule.PollingState;

/**
 * Reports DOWN when the last successful polling cycle is older than twice the
 * configured interval — a bot that silently stopped fetching news looks alive
 * to a plain liveness probe.
 */
@Component("newsPolling")
public class NewsPollingHealthIndicator implements HealthIndicator {

    private final PollingState state;
    private final BotProperties properties;

    public NewsPollingHealthIndicator(PollingState state, BotProperties properties) {
        this.state = state;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Duration tolerance = Duration.ofMinutes(2L * properties.getPollIntervalMinutes());
        Optional<Instant> lastPoll = state.lastSuccessfulPoll();
        Instant reference = lastPoll.orElseGet(state::startedAt);
        Duration age = Duration.between(reference, Instant.now());

        Health.Builder builder = age.compareTo(tolerance) <= 0 ? Health.up() : Health.down();
        return builder
            .withDetail("lastSuccessfulPoll", lastPoll.map(Instant::toString).orElse("never"))
            .withDetail("ageSeconds", age.toSeconds())
            .withDetail("toleranceSeconds", tolerance.toSeconds())
            .withDetail("runs", state.runs())
            .withDetail("messagesSent", state.messagesSent())
            .build();
    }
}
