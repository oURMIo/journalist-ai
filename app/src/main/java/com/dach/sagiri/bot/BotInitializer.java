package com.dach.sagiri.bot;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.telegram.UpdateHandler;
import com.pengrad.telegrambot.TelegramBot;

/**
 * Starts long polling. Kept from the original skeleton; the only change is that
 * updates now go to {@link UpdateHandler} instead of the echo handler.
 */
@Component
public class BotInitializer {

    private static final Logger logger = LoggerFactory.getLogger(BotInitializer.class);

    private final TelegramBot bot;
    private final UpdateHandler updateHandler;

    public BotInitializer(TelegramBot bot, UpdateHandler updateHandler) {
        this.bot = bot;
        this.updateHandler = updateHandler;
    }

    @PostConstruct
    public void start() {
        logger.info("Starting Telegram long polling");
        bot.setUpdatesListener(
            updateHandler::handle,
            e -> {
                if (e.response() != null) {
                    logger.error("Telegram getUpdates failed: {} {}",
                        e.response().errorCode(), e.response().description());
                } else {
                    logger.error("Telegram long polling error", e);
                }
            });
    }
}
