package com.dach.sagiri.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.pengrad.telegrambot.TelegramBot;

@Configuration
public class BotConfig {

    private static final Logger logger = LoggerFactory.getLogger(BotConfig.class);

    @Bean
    public TelegramBot telegramBot(BotProperties properties) {
        if (StringUtils.isBlank(properties.getToken())) {
            throw new IllegalStateException(
                "BOT_TOKEN is not set. Copy .env.example to .env and fill it in.");
        }
        if (properties.getAllowedChatIds().isEmpty()) {
            throw new IllegalStateException(
                "ALLOWED_CHAT_IDS is not set. Without it the bot would answer in any chat "
                + "it is added to.");
        }
        logger.info("Creating Telegram bot for {} allowed chat(s)",
            properties.getAllowedChatIds().size());
        return new TelegramBot.Builder(properties.getToken()).build();
    }

    /** Shared HTTP client for feeds and the optional LLM call. Redirects are followed. */
    @Bean
    public HttpClient newsHttpClient(BotProperties properties) {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(properties.getHttp().getTimeoutSeconds()))
            .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
