package com.dach.sagiri.topic;

import com.google.gson.Gson;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.request.CloseForumTopic;
import com.pengrad.telegrambot.request.CreateForumTopic;
import com.pengrad.telegrambot.request.PinChatMessage;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.CreateForumTopicResponse;
import com.pengrad.telegrambot.response.SendResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.config.TopicPresetsConfig;
import com.dach.sagiri.telegram.ChatRateLimiter;
import com.dach.sagiri.telegram.TelegramApi;
import com.dach.sagiri.telegram.TelegramSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The scenario spec section 9a calls out explicitly: the bot creates a topic,
 * and Telegram then delivers a {@code forum_topic_created} update for that very
 * topic. Exactly one registry row and exactly one welcome message must result.
 */
class TopicProvisioningIdempotencyTest {

    private static final long CHAT_ID = -1001234567890L;
    private static final long THREAD_ID = 777L;
    private static final Gson GSON = new Gson();

    private ScriptedBot bot;
    private TelegramSender sender;
    private FakeTopicStore store;
    private TopicRegistryService registry;
    private TopicWelcomeService welcomeService;
    private DefaultTopicProvisioningService provisioning;

    @BeforeEach
    void setUp() {
        BotProperties properties = new BotProperties();
        properties.setSendRatePerMinute(6000);
        properties.setShutdownDrainSeconds(5);
        properties.setWelcomePin(true);

        bot = new ScriptedBot();
        ChatRateLimiter limiter = new ChatRateLimiter(properties);
        TelegramApi api = new TelegramApi(bot, limiter);
        sender = new TelegramSender(api, properties);
        sender.start();

        store = new FakeTopicStore();
        TopicSlugParser slugParser = new TopicSlugParser(properties);
        TopicQueryResolver queryResolver = new TopicQueryResolver(new TopicPresetsConfig());
        registry = new TopicRegistryService(store.repository(), slugParser, queryResolver);

        MessageTemplates messages = new MessageTemplates();
        messages.setWelcome("Welcome to {slug}");
        messages.setSourcesFallback("all English sources");
        WelcomeMessageFormatter formatter = new WelcomeMessageFormatter(messages, properties);

        welcomeService = new TopicWelcomeService(store.repository(), queryResolver, formatter,
            sender, api, properties);
        provisioning = new DefaultTopicProvisioningService(api, registry, welcomeService,
            slugParser, properties);
    }

    @AfterEach
    void tearDown() {
        sender.shutdown();
    }

    @Test
    void a_bot_created_topic_survives_its_own_forum_topic_created_update() {
        ProvisionResult result = provisioning.provision(CHAT_ID, "biotech");

        assertThat(result.status()).isEqualTo(ProvisionResult.Status.CREATED);
        assertThat(result.threadId()).isEqualTo(THREAD_ID);

        // Telegram now delivers the creation update for the very same topic.
        Optional<TopicEntity> fromUpdate =
            registry.register(CHAT_ID, THREAD_ID, "news-biotech", false);
        fromUpdate.ifPresent(welcomeService::ensureWelcome);

        assertThat(store.rows()).as("exactly one registry row").hasSize(1);
        assertThat(bot.sentMessages()).as("exactly one welcome message").hasSize(1);
        assertThat(store.rows().getFirst().getWelcomeMessageId()).isEqualTo(555L);
        assertThat(store.rows().getFirst().isCreatedByBot()).isTrue();
    }

    @Test
    void provisioning_the_same_subject_twice_does_not_create_a_second_topic() {
        provisioning.provision(CHAT_ID, "biotech");

        ProvisionResult second = provisioning.provision(CHAT_ID, "biotech");

        assertThat(second.status()).isEqualTo(ProvisionResult.Status.ALREADY_EXISTS);
        assertThat(second.threadId()).isEqualTo(THREAD_ID);
        assertThat(bot.createdTopics()).hasSize(1);
        assertThat(bot.sentMessages()).hasSize(1);
    }

    @Test
    void a_prefixed_slug_is_not_doubled() {
        ProvisionResult result = provisioning.provision(CHAT_ID, "news-biotech");

        assertThat(result.slug()).isEqualTo("biotech");
        assertThat(bot.createdTopics()).singleElement()
            .satisfies(request ->
                assertThat(request.getParameters()).containsEntry("name", "news-biotech"));
    }

    @Test
    void an_invalid_slug_creates_nothing() {
        ProvisionResult result = provisioning.provision(CHAT_ID, "!");

        assertThat(result.status()).isEqualTo(ProvisionResult.Status.INVALID_SLUG);
        assertThat(bot.createdTopics()).isEmpty();
        assertThat(store.rows()).isEmpty();
    }

    @Test
    void a_missing_pin_permission_still_leaves_a_working_topic() {
        bot.failPinning();

        ProvisionResult result = provisioning.provision(CHAT_ID, "biotech");

        assertThat(result.status()).isEqualTo(ProvisionResult.Status.CREATED);
        assertThat(store.rows().getFirst().getWelcomeMessageId()).isEqualTo(555L);
    }

    @Test
    void closing_a_topic_marks_it_closed_without_dropping_it_from_the_registry() {
        provisioning.provision(CHAT_ID, "biotech");

        provisioning.close(CHAT_ID, THREAD_ID);

        assertThat(bot.closedTopics()).hasSize(1);
        TopicEntity row = store.rows().getFirst();
        assertThat(row.isClosed()).isTrue();
        assertThat(row.isActive())
            .as("closed is Telegram's state; the topic stays ours")
            .isTrue();
    }

    @Test
    void a_closed_topic_that_gets_renamed_stays_closed() {
        provisioning.provision(CHAT_ID, "biotech");
        provisioning.close(CHAT_ID, THREAD_ID);

        registry.register(CHAT_ID, THREAD_ID, "news-genomics", false);

        assertThat(store.rows().getFirst().getSlug()).isEqualTo("genomics");
        assertThat(store.rows().getFirst().isClosed()).isTrue();
    }

    @Test
    void icon_colour_is_deterministic_and_always_from_the_allowed_palette() {
        int first = DefaultTopicProvisioningService.iconColorFor("ai-safety");
        int again = DefaultTopicProvisioningService.iconColorFor("ai-safety");

        assertThat(first).isEqualTo(again);
        assertThat(DefaultTopicProvisioningService.PALETTE).contains(first);
    }

    /** Answers the three calls provisioning makes, and records what it was asked. */
    private static final class ScriptedBot extends TelegramBot {

        private final List<BaseRequest<?, ?>> requests = new ArrayList<>();
        private boolean pinningAllowed = true;

        private ScriptedBot() {
            super("0:test-token");
        }

        void failPinning() {
            pinningAllowed = false;
        }

        List<BaseRequest<?, ?>> createdTopics() {
            return requests.stream().filter(CreateForumTopic.class::isInstance).toList();
        }

        List<BaseRequest<?, ?>> sentMessages() {
            return requests.stream().filter(SendMessage.class::isInstance).toList();
        }

        List<BaseRequest<?, ?>> closedTopics() {
            return requests.stream().filter(CloseForumTopic.class::isInstance).toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends BaseRequest<T, R>, R extends BaseResponse> R execute(
            BaseRequest<T, R> request) {

            requests.add(request);
            if (request instanceof CreateForumTopic) {
                return (R) GSON.fromJson("""
                    {"ok":true,"result":{"message_thread_id":777,"name":"news-biotech",
                     "icon_color":9367192}}""", CreateForumTopicResponse.class);
            }
            if (request instanceof SendMessage) {
                return (R) GSON.fromJson("""
                    {"ok":true,"result":{"message_id":555,"date":0,
                     "chat":{"id":-1001234567890}}}""", SendResponse.class);
            }
            if (request instanceof PinChatMessage && !pinningAllowed) {
                return (R) GSON.fromJson("""
                    {"ok":false,"error_code":400,
                     "description":"Bad Request: not enough rights to pin a message"}""",
                    BaseResponse.class);
            }
            return (R) GSON.fromJson("{\"ok\":true,\"result\":true}", BaseResponse.class);
        }
    }

    /**
     * In-memory stand-in for {@link TopicRepository}. Only the methods the flow
     * touches are wired, but they behave like the real thing — including the
     * conditional welcome claim that makes the second greeting impossible.
     */
    private static final class FakeTopicStore {

        private final Map<Long, TopicEntity> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong();
        private final TopicRepository repository = mock(TopicRepository.class);

        private FakeTopicStore() {
            when(repository.findByChatIdAndThreadId(anyLong(), anyLong())).thenAnswer(call ->
                byId.values().stream()
                    .filter(topic -> topic.getChatId() == (long) call.getArgument(0)
                                     && topic.getThreadId() == (long) call.getArgument(1))
                    .findFirst());

            when(repository.findByChatIdAndSlugAndActiveTrue(anyLong(), anyString()))
                .thenAnswer(call -> byId.values().stream()
                    .filter(topic -> topic.getChatId() == (long) call.getArgument(0)
                                     && topic.getSlug().equals(call.getArgument(1))
                                     && topic.isActive())
                    .findFirst());

            when(repository.save(any(TopicEntity.class))).thenAnswer(call -> {
                TopicEntity topic = call.getArgument(0);
                if (topic.getId() == null) {
                    topic.setId(ids.incrementAndGet());
                    topic.setCreatedAt(Instant.now());
                }
                topic.setUpdatedAt(Instant.now());
                byId.put(topic.getId(), topic);
                return topic;
            });

            when(repository.findById(any())).thenAnswer(call ->
                Optional.ofNullable(byId.get(call.<Long>getArgument(0))));

            when(repository.claimWelcome(any(), anyLong(), any())).thenAnswer(call -> {
                TopicEntity topic = byId.get(call.<Long>getArgument(0));
                if (topic == null || topic.getWelcomeMessageId() != null) {
                    return 0;
                }
                topic.setWelcomeMessageId(call.getArgument(1));
                return 1;
            });

            when(repository.setWelcomeMessageId(any(), any(), any())).thenAnswer(call -> {
                TopicEntity topic = byId.get(call.<Long>getArgument(0));
                if (topic == null) {
                    return 0;
                }
                topic.setWelcomeMessageId(call.getArgument(1));
                return 1;
            });

            when(repository.markPolled(any(), any())).thenReturn(1);
            when(repository.findByActiveTrueAndClosedFalseOrderByChatIdAscThreadIdAsc())
                .thenAnswer(call -> byId.values().stream()
                    .filter(topic -> topic.isActive() && !topic.isClosed())
                    .toList());
        }

        TopicRepository repository() {
            return repository;
        }

        List<TopicEntity> rows() {
            return List.copyOf(byId.values());
        }
    }
}
