package com.dach.sagiri.telegram;

import com.google.gson.Gson;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.SendResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.BotProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Telegram layer is exercised through a recording bot: no network, but the
 * exact request parameters the API would receive are asserted.
 */
class TelegramSenderTest {

    private static final long CHAT_ID = -1001234567890L;
    private static final Gson GSON = new Gson();

    private RecordingBot bot;
    private TelegramSender sender;

    @BeforeEach
    void setUp() {
        BotProperties properties = new BotProperties();
        // Fast throttle: the pacing itself is covered by ChatRateLimiter.
        properties.setSendRatePerMinute(6000);
        properties.setShutdownDrainSeconds(5);

        bot = new RecordingBot();
        sender = new TelegramSender(new TelegramApi(bot, new ChatRateLimiter(properties)),
            properties);
        sender.start();
    }

    @AfterEach
    void tearDown() {
        sender.shutdown();
    }

    private static SendResponse ok(int messageId) {
        return GSON.fromJson("""
            {"ok":true,"result":{"message_id":%d,"date":0,"chat":{"id":-1001234567890}}}"""
            .formatted(messageId), SendResponse.class);
    }

    private static SendResponse tooManyRequests() {
        return GSON.fromJson("""
            {"ok":false,"error_code":429,"description":"Too Many Requests",
             "parameters":{"retry_after":0}}""", SendResponse.class);
    }

    @Test
    void sends_the_message_into_the_originating_topic() {
        bot.responses.add(ok(11));

        sender.sendBlocking(new OutgoingMessage(CHAT_ID, 42L, "<b>hi</b>", false, false));

        Map<String, Object> params = bot.requests.getFirst().getParameters();
        assertThat(params).containsEntry("message_thread_id", 42L);
        assertThat(params).containsEntry("chat_id", CHAT_ID);
        assertThat(params).containsEntry("text", "<b>hi</b>");
        assertThat(String.valueOf(params.get("parse_mode"))).isEqualTo("HTML");
    }

    @Test
    void omits_the_thread_id_for_the_general_topic() {
        bot.responses.add(ok(12));

        sender.sendBlocking(new OutgoingMessage(CHAT_ID, null, "hello", false, false));

        assertThat(bot.requests.getFirst().getParameters()).doesNotContainKey("message_thread_id");
    }

    @Test
    void link_preview_follows_the_outgoing_message() {
        bot.responses.add(ok(13));
        bot.responses.add(ok(14));

        sender.sendBlocking(new OutgoingMessage(CHAT_ID, 42L, "first", true, false));
        sender.sendBlocking(new OutgoingMessage(CHAT_ID, 42L, "second", false, false));

        assertThat(bot.requests).hasSize(2);
        assertThat(bot.requests.get(0).getParameters()).containsKey("link_preview_options");
        assertThat(bot.requests.get(1).getParameters()).containsKey("link_preview_options");
    }

    @Test
    void a_429_is_retried_instead_of_dropping_the_message() {
        bot.responses.add(tooManyRequests());
        bot.responses.add(ok(15));

        SendResponse response =
            sender.sendBlocking(new OutgoingMessage(CHAT_ID, 42L, "text", false, false));

        assertThat(response.isOk()).isTrue();
        assertThat(bot.requests).as("the first attempt was rejected with 429").hasSize(2);
    }

    @Test
    void a_permanent_error_is_reported_rather_than_retried_forever() {
        bot.responses.add(GSON.fromJson(
            """
                {"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""",
            SendResponse.class));

        SendResponse response =
            sender.sendBlocking(new OutgoingMessage(CHAT_ID, 42L, "text", false, false));

        assertThat(response.isOk()).isFalse();
        assertThat(bot.requests).hasSize(1);
    }

    @Test
    void an_oversized_body_is_trimmed_to_the_telegram_limit() {
        bot.responses.add(ok(16));

        sender.sendBlocking(
            new OutgoingMessage(CHAT_ID, 42L, "x".repeat(9000), false, false));

        String text = String.valueOf(bot.requests.getFirst().getParameters().get("text"));
        assertThat(text).hasSize(HtmlText.MAX_MESSAGE_LENGTH);
    }

    /** Captures requests and replays canned responses in order. */
    private static final class RecordingBot extends TelegramBot {

        private final List<BaseRequest<?, ?>> requests = new ArrayList<>();
        private final Deque<BaseResponse> responses = new ArrayDeque<>();

        private RecordingBot() {
            super("0:test-token");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends BaseRequest<T, R>, R extends BaseResponse> R execute(
            BaseRequest<T, R> request) {

            requests.add(request);
            BaseResponse response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("No canned response left for " + request);
            }
            return (R) response;
        }
    }
}
