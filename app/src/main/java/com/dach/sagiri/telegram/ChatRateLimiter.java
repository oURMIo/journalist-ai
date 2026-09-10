package com.dach.sagiri.telegram;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.dach.sagiri.config.BotProperties;
import org.springframework.stereotype.Component;

/**
 * Per-chat throttle. Telegram allows roughly 20 messages per minute into one
 * group and the topics of that group share the quota, so the limit is keyed by
 * chat, never by thread.
 */
@Component
public class ChatRateLimiter {

    private final long minIntervalMillis;
    private final Map<Long, Long> nextAllowedAt = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public ChatRateLimiter(BotProperties properties) {
        int perMinute = Math.max(1, properties.getSendRatePerMinute());
        this.minIntervalMillis = 60_000L / perMinute;
    }

    /** Blocks until this chat is allowed to receive one more message. */
    public void acquire(long chatId) throws InterruptedException {
        while (true) {
            long waitMillis;
            synchronized (lock) {
                long now = System.currentTimeMillis();
                long next = nextAllowedAt.getOrDefault(chatId, 0L);
                if (now >= next) {
                    nextAllowedAt.put(chatId, now + minIntervalMillis);
                    return;
                }
                waitMillis = next - now;
            }
            Thread.sleep(waitMillis);
        }
    }

    /** Applies a Telegram-imposed pause, e.g. the {@code retry_after} of a 429. */
    public void penalize(long chatId, Duration pause) {
        synchronized (lock) {
            long until = System.currentTimeMillis() + Math.max(0, pause.toMillis());
            nextAllowedAt.merge(chatId, until, Math::max);
        }
    }

    public long minIntervalMillis() {
        return minIntervalMillis;
    }
}
