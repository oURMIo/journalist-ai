package com.dach.sagiri.schedule;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Shared counters of the polling loop, read by {@code /status} and the health check. */
@Component
public class PollingState {

    private final Instant startedAt = Instant.now();
    private final AtomicReference<Instant> lastSuccessfulPoll = new AtomicReference<>();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong runs = new AtomicLong();

    public void recordSuccess(int sent) {
        lastSuccessfulPoll.set(Instant.now());
        messagesSent.addAndGet(sent);
        runs.incrementAndGet();
    }

    public Optional<Instant> lastSuccessfulPoll() {
        return Optional.ofNullable(lastSuccessfulPoll.get());
    }

    public Instant startedAt() {
        return startedAt;
    }

    public long messagesSent() {
        return messagesSent.get();
    }

    public long runs() {
        return runs.get();
    }
}
