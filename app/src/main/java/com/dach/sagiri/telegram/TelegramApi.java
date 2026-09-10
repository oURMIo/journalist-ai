package com.dach.sagiri.telegram;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.response.BaseResponse;

/**
 * Thin wrapper over {@link TelegramBot#execute} that applies the shared per-chat
 * throttle and retries on {@code 429 Too Many Requests} / 5xx.
 * <p>
 * Every call that costs chat quota goes through here, including topic
 * management calls such as {@code createForumTopic}.
 */
@Component
public class TelegramApi {

    private static final Logger logger = LoggerFactory.getLogger(TelegramApi.class);

    private static final int MAX_ATTEMPTS = 4;
    private static final int TOO_MANY_REQUESTS = 429;

    private final TelegramBot bot;
    private final ChatRateLimiter rateLimiter;

    public TelegramApi(TelegramBot bot, ChatRateLimiter rateLimiter) {
        this.bot = bot;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Executes a request against the chat quota of {@code chatId}.
     *
     * @return the response; check {@link BaseResponse#isOk()} — a failed API call
     *     is not an exception, callers decide whether it is fatal
     */
    public <T extends BaseRequest<T, R>, R extends BaseResponse> R execute(
        long chatId, BaseRequest<T, R> request) {

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                rateLimiter.acquire(chatId);
                R response = bot.execute(request);
                if (response.isOk()) {
                    return response;
                }
                if (response.errorCode() == TOO_MANY_REQUESTS) {
                    Duration pause = retryAfter(response);
                    logger.warn("Telegram 429 for chat {}, honouring retry_after={}s (attempt {}/{})",
                        chatId, pause.toSeconds(), attempt, MAX_ATTEMPTS);
                    rateLimiter.penalize(chatId, pause);
                    continue;
                }
                if (response.errorCode() >= 500) {
                    Duration pause = backoff(attempt);
                    logger.warn("Telegram {} for chat {}: {} — retrying in {}ms (attempt {}/{})",
                        response.errorCode(), chatId, response.description(), pause.toMillis(),
                        attempt, MAX_ATTEMPTS);
                    Thread.sleep(pause.toMillis());
                    continue;
                }
                // 4xx other than 429 is a permanent client error: no point retrying.
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while calling Telegram", e);
            } catch (RuntimeException e) {
                lastFailure = e;
                logger.warn("Telegram call failed (attempt {}/{}): {}", attempt, MAX_ATTEMPTS,
                    e.toString());
                sleepQuietly(backoff(attempt));
            }
        }
        throw new IllegalStateException("Telegram call failed after " + MAX_ATTEMPTS + " attempts",
            lastFailure);
    }

    private static Duration retryAfter(BaseResponse response) {
        Integer seconds = response.parameters() == null ? null : response.parameters().retryAfter();
        // +1s of slack: retry_after is the earliest moment Telegram will accept us again.
        return Duration.ofSeconds(seconds == null ? 5 : seconds + 1L);
    }

    private static Duration backoff(int attempt) {
        return Duration.ofMillis(Math.min(8_000L, 500L * (1L << (attempt - 1))));
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
