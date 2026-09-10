package com.dach.sagiri.topic;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;

/**
 * Creates the topics listed in {@code BOOTSTRAP_TOPICS} on start-up.
 * <p>
 * Idempotent by construction: provisioning checks the registry first, so
 * restarting the container neither duplicates topics nor re-sends welcomes.
 * A failure here never prevents the bot from starting.
 */
@Component
public class TopicBootstrapRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(TopicBootstrapRunner.class);

    private final BotProperties properties;
    private final TopicProvisioningService provisioning;

    public TopicBootstrapRunner(BotProperties properties,
        TopicProvisioningService provisioning) {
        this.properties = properties;
        this.provisioning = provisioning;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> slugs = properties.getBootstrapTopics();
        if (slugs.isEmpty()) {
            return;
        }
        for (Long chatId : properties.getAllowedChatIds()) {
            try {
                List<ProvisionResult> results = provisioning.provisionAll(chatId, slugs);
                results.forEach(result -> logger.info("Bootstrap {} in chat {}: {} ({})",
                    result.slug(), chatId, result.status(), result.message()));
            } catch (RuntimeException e) {
                logger.error("Bootstrap of topics failed for chat {}", chatId, e);
            }
        }
    }
}
