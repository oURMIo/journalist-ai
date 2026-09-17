package com.dach.sagiri;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.config.TopicPresetsConfig;
import com.dach.sagiri.health.NewsPollingHealthIndicator;
import com.dach.sagiri.schedule.NewsPollingJob;
import com.dach.sagiri.summary.Summarizer;
import com.dach.sagiri.telegram.CommandRouter;
import com.dach.sagiri.telegram.command.BotCommand;
import com.pengrad.telegrambot.TelegramBot;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real application context — every bean, the YAML configuration and
 * the Flyway migration — with Telegram replaced by a mock so nothing is sent.
 * <p>
 * Needs a database; enable with {@code INTEGRATION_TESTS=true}.
 */
// bot.token is deliberately not set: the context must start from the YAML
// defaults alone, which proves the BOT_TOKEN placeholder chain resolves even
// when neither the environment variable nor config.properties is present.
@SpringBootTest(properties = {
    "bot.username=journalist_test_bot",
    "bot.allowed-chat-ids=-1001234567890",
    "server.port=0"
})
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
class ApplicationContextTest {

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    /** Keeps long polling from ever reaching the network. */
    @MockitoBean
    private TelegramBot telegramBot;

    @Autowired
    private BotProperties properties;

    @Autowired
    private MessageTemplates messages;

    @Autowired
    private TopicPresetsConfig presets;

    @Autowired
    private List<BotCommand> commands;

    @Autowired
    private CommandRouter commandRouter;

    @Autowired
    private NewsPollingJob pollingJob;

    @Autowired
    private NewsPollingHealthIndicator health;

    @Autowired
    private Summarizer summarizer;

    @Test
    void the_context_starts_and_wires_every_moving_part() {
        assertThat(pollingJob).isNotNull();
        assertThat(summarizer).isNotNull();
        assertThat(health.health()).isNotNull();
    }

    @Test
    void configuration_is_bound_from_yaml_and_environment() {
        assertThat(properties.getAllowedChatIds()).containsExactly(-1001234567890L);
        assertThat(properties.getTopicPrefix()).isEqualTo("news-");
        assertThat(properties.getPollIntervalMinutes()).isEqualTo(30);
        assertThat(properties.getMaxItemsPerTopic()).isEqualTo(5);
        assertThat(properties.getLinkPreview()).isEqualTo(BotProperties.LinkPreview.FIRST_ONLY);
    }

    @Test
    void user_facing_texts_are_loaded_from_messages_yml() {
        assertThat(messages.getWelcome()).contains("{slug}").contains("{interval}");
        assertThat(messages.getHelp()).contains("/newtopic");
        assertThat(messages.getNotAdmin()).isNotBlank();
        assertThat(messages.getClosetopicOk()).isNotBlank();
    }

    @Test
    void presets_are_loaded_from_topic_presets_yml() {
        assertThat(presets.getPresets()).containsKeys("it", "science", "ai");
        assertThat(presets.find("it")).hasValueSatisfying(preset ->
            assertThat(preset.getFeeds()).isNotEmpty());
    }

    @Test
    void every_command_from_the_spec_is_registered() {
        assertThat(commands).isNotEmpty();
        List<String> expected = List.of("/start", "/help", "/newtopic", "/newtopics", "/presets",
            "/bind", "/unbind", "/renametopic", "/closetopic", "/status", "/now", "/topics",
            "/filter", "/exclude", "/include");

        assertThat(expected).allSatisfy(name ->
            assertThat(commandRouter.find(name)).as(name).isPresent());
    }

    @Test
    void only_start_and_help_are_open_to_non_admins() {
        assertThat(commandRouter.find("/help")).hasValueSatisfying(command ->
            assertThat(command.adminOnly()).isFalse());
        assertThat(commandRouter.find("/newtopic")).hasValueSatisfying(command ->
            assertThat(command.adminOnly()).isTrue());
    }

    @Test
    void ollama_is_the_default_summarizer() {
        // docker compose starts an ollama container next to the bot, so the local
        // model is the default. It degrades to extractive if the server is absent.
        assertThat(summarizer.getClass().getSimpleName()).isEqualTo("OllamaSummarizer");
        assertThat(properties.getOllama().getBaseUrl()).isEqualTo("http://ollama:11434");
        assertThat(properties.getOllama().getModel()).isEqualTo("llama3.2:3b");
    }
}
