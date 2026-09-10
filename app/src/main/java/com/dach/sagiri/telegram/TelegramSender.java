package com.dach.sagiri.telegram;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.pengrad.telegrambot.model.LinkPreviewOptions;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;

/**
 * The single outbound path for messages: one global queue drained by one worker,
 * throttled per chat and retried on 429 by {@link TelegramApi}.
 * <p>
 * {@link #enqueue} returns a future so the polling job can mark an article as
 * sent only once Telegram actually accepted it.
 */
@Component
public class TelegramSender {

    private static final Logger logger = LoggerFactory.getLogger(TelegramSender.class);

    private final TelegramApi api;
    private final BotProperties properties;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();

    private volatile boolean accepting = true;
    private volatile boolean running = true;
    private Thread worker;

    public TelegramSender(TelegramApi api, BotProperties properties) {
        this.api = api;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        worker = new Thread(this::drainLoop, "telegram-sender");
        worker.setDaemon(false);
        worker.start();
    }

    /** Queues a message and completes when Telegram accepted or definitively rejected it. */
    public CompletableFuture<SendResponse> enqueue(OutgoingMessage message) {
        CompletableFuture<SendResponse> future = new CompletableFuture<>();
        if (!accepting) {
            future.completeExceptionally(new IllegalStateException("Sender is shutting down"));
            return future;
        }
        queue.add(new Task(message, future));
        return future;
    }

    /**
     * Queues a message and waits for it. Used where the {@code message_id} of the
     * result is needed (the pinned welcome message).
     */
    public SendResponse sendBlocking(OutgoingMessage message) {
        try {
            // Worst case the queue ahead of us is long; the throttle sets the pace.
            long timeoutSeconds = 60 + queue.size() * 5L;
            return enqueue(message).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending message", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed to send message", e);
        }
    }

    /** Convenience for command handlers: always answers inside the originating topic. */
    public void reply(long chatId, Long threadId, String html) {
        enqueue(OutgoingMessage.reply(chatId, threadId, html));
    }

    public int queueSize() {
        return queue.size();
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            Task task = null;
            try {
                task = queue.poll(200, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }
                task.future.complete(deliver(task.message));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                if (task == null) {
                    logger.error("Telegram sender loop failed", e);
                    continue;
                }
                logger.error("Failed to deliver message to chat {}", task.message.chatId(), e);
                task.future.completeExceptionally(e);
            }
        }
        logger.info("Telegram sender stopped, queue drained");
    }

    private SendResponse deliver(OutgoingMessage message) {
        SendMessage request = new SendMessage(message.chatId(),
            HtmlText.fitMessage(message.html()))
            .parseMode(ParseMode.HTML);

        if (message.threadId() != null) {
            // Without message_thread_id everything lands in General.
            request.messageThreadId(message.threadId());
        }
        if (message.silent()) {
            request.disableNotification(true);
        }
        request.linkPreviewOptions(new LinkPreviewOptions().isDisabled(!message.linkPreview()));

        SendResponse response = api.execute(message.chatId(), request);
        if (!response.isOk()) {
            logger.warn("sendMessage rejected for chat {} thread {}: {} {}",
                message.chatId(), message.threadId(), response.errorCode(),
                response.description());
        }
        return response;
    }

    /**
     * Stops accepting new work and gives the worker a bounded window to flush what
     * is already queued, so pending {@code markSent} calls are not lost.
     */
    @PreDestroy
    public void shutdown() {
        accepting = false;
        running = false;
        if (worker == null) {
            return;
        }
        try {
            worker.join(properties.getShutdownDrainSeconds() * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int abandoned = queue.size();
        if (abandoned > 0) {
            logger.warn("Shutdown drain window elapsed, {} message(s) were not sent", abandoned);
            queue.forEach(task -> task.future.completeExceptionally(
                new IllegalStateException("Sender shut down before the message was sent")));
            queue.clear();
        }
    }

    private record Task(OutgoingMessage message, CompletableFuture<SendResponse> future) {
    }
}
