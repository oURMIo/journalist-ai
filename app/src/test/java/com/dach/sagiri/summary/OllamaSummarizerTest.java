package com.dach.sagiri.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.dedup.SeenArticleService;
import com.dach.sagiri.dedup.UrlCanonicalizer;
import com.dach.sagiri.news.ArticleContextFetcher;
import com.dach.sagiri.news.model.NewsItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Ollama summariser against a stub server on loopback: no model download, no
 * network, but the real request body and the real failure paths.
 */
class OllamaSummarizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private BotProperties.Ollama settings;
    private OllamaSummarizer summarizer;
    private SummaryCacheRepository cache;
    private ArticleContextFetcher articles;

    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>(
        "{\"response\":\"A chipmaker announced a 2nm process. Production starts next year.\","
        + "\"done\":true}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/generate", this::handle);
        server.start();

        settings = new BotProperties.Ollama();
        settings.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        settings.setModel("llama3.2:3b");
        settings.setTimeoutSeconds(5);

        articles = noContext();
        summarizer = new OllamaSummarizer(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            new ExtractiveSummarizer(), emptyCache(), seenArticles(), articles, settings);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8));
        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        exchange.close();
    }

    private SummaryCacheRepository emptyCache() {
        cache = mock(SummaryCacheRepository.class);
        when(cache.findById(anyString())).thenReturn(Optional.empty());
        when(cache.save(any(SummaryCacheEntity.class))).thenAnswer(call -> call.getArgument(0));
        return cache;
    }

    /** The article page is a collaborator like any other: mocked, never fetched. */
    private static ArticleContextFetcher noContext() {
        ArticleContextFetcher fetcher = mock(ArticleContextFetcher.class);
        when(fetcher.contextFor(any(NewsItem.class), nullable(String.class))).thenReturn("");
        return fetcher;
    }

    private static SeenArticleService seenArticles() {
        return new SeenArticleService(mock(
            com.dach.sagiri.dedup.SeenArticleRepository.class), new UrlCanonicalizer());
    }

    private static NewsItem item(String description) {
        return new NewsItem("id-1", "Chipmaker unveils a 2nm process",
            "https://example.com/story", "Example", Instant.now(), description, null);
    }

    @Test
    void summarises_an_article_through_the_local_model() {
        String summary = summarizer.summarize(item("<p>Long feed description.</p>"));

        assertThat(summary)
            .isEqualTo("A chipmaker announced a 2nm process. Production starts next year.");
    }

    @Test
    void sends_a_non_streaming_request_with_the_configured_model() throws IOException {
        summarizer.summarize(item("Some description."));

        assertThat(requestBodies).hasSize(1);
        JsonNode body = JSON.readTree(requestBodies.getFirst());
        assertThat(body.path("model").asText()).isEqualTo("llama3.2:3b");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("options").path("num_predict").asInt()).isPositive();
        assertThat(body.path("prompt").asText())
            .contains("1-3 factual sentences")
            .contains("Chipmaker unveils a 2nm process")
            .contains("Some description.");
    }

    @Test
    void sends_the_cleaned_description_never_the_raw_html() throws IOException {
        summarizer.summarize(item(
            "<p>Text with <b>markup</b>.</p><script>trackingPixel()</script>"));

        JsonNode body = JSON.readTree(requestBodies.getFirst());
        assertThat(body.path("prompt").asText())
            .contains("Text with markup.")
            .doesNotContain("<b>")
            .doesNotContain("<script")
            .doesNotContain("trackingPixel");
    }

    @Test
    void caches_the_summary_so_the_model_is_not_asked_twice() {
        NewsItem article = item("Description.");
        summarizer.summarize(article);

        when(cache.findById(anyString())).thenReturn(Optional.of(cached("Cached summary.")));

        assertThat(summarizer.summarize(article)).isEqualTo("Cached summary.");
        assertThat(requestBodies).as("the model was asked only once").hasSize(1);
    }

    @Test
    void a_missing_model_falls_back_instead_of_leaving_the_news_empty() {
        status.set(404);
        responseBody.set("{\"error\":\"model 'llama3.2:3b' not found, try pulling it first\"}");

        String summary = summarizer.summarize(item("The feed description stands in. It works."));

        assertThat(summary).isEqualTo("The feed description stands in. It works.");
    }

    @Test
    void a_server_error_falls_back() {
        status.set(500);
        responseBody.set("{\"error\":\"boom\"}");

        assertThat(summarizer.summarize(item("Fallback text here.")))
            .isEqualTo("Fallback text here.");
    }

    @Test
    void an_unreachable_server_falls_back() {
        settings.setBaseUrl("http://127.0.0.1:1");

        assertThat(summarizer.summarize(item("Fallback text here.")))
            .isEqualTo("Fallback text here.");
    }

    @Test
    void an_empty_answer_falls_back() {
        responseBody.set("{\"response\":\"   \",\"done\":true}");

        assertThat(summarizer.summarize(item("Fallback text here.")))
            .isEqualTo("Fallback text here.");
    }

    @Test
    void a_refusal_is_not_a_summary_and_is_never_cached() {
        responseBody.set(
            "{\"response\":\"I don't have any information about the news item.\",\"done\":true}");

        String summary = summarizer.summarize(item("The feed description stands in. It works."));

        assertThat(summary).isEqualTo("The feed description stands in. It works.");
        verify(cache, never()).save(any(SummaryCacheEntity.class));
    }

    @Test
    void an_answer_that_is_a_summary_is_cached() {
        summarizer.summarize(item("Description."));

        ArgumentCaptor<SummaryCacheEntity> saved = ArgumentCaptor.forClass(
            SummaryCacheEntity.class);
        verify(cache).save(saved.capture());
        assertThat(saved.getValue().getSummary())
            .isEqualTo("A chipmaker announced a 2nm process. Production starts next year.");
    }

    @Test
    void sends_the_fetched_article_context_when_the_feed_said_almost_nothing() throws IOException {
        when(articles.contextFor(any(NewsItem.class), nullable(String.class)))
            .thenReturn("The plant in Dresden opened on Tuesday.");

        summarizer.summarize(item("Read more"));

        JsonNode body = JSON.readTree(requestBodies.getFirst());
        assertThat(body.path("prompt").asText())
            .contains("Source: Example")
            .contains("Article: The plant in Dresden opened on Tuesday.");
    }

    @Test
    void falls_back_to_the_fetched_context_not_to_the_headline() {
        when(articles.contextFor(any(NewsItem.class), nullable(String.class)))
            .thenReturn("The plant in Dresden opened on Tuesday. It employs three thousand.");
        responseBody.set(
            "{\"response\":\"Unfortunately, there is no information available.\",\"done\":true}");

        String summary = summarizer.summarize(item(""));

        assertThat(summary)
            .isEqualTo("The plant in Dresden opened on Tuesday. It employs three thousand.");
        verify(cache, never()).save(any(SummaryCacheEntity.class));
    }

    @Test
    void recognises_the_answers_a_small_model_gives_when_it_has_nothing() {
        assertThat(LlmSummarizer.isNonAnswer("I don't have any information about the news item."))
            .isTrue();
        assertThat(LlmSummarizer.isNonAnswer("Unfortunately, I cannot summarise this article."))
            .isTrue();
        assertThat(LlmSummarizer.isNonAnswer("There is no information in the provided text."))
            .isTrue();
        assertThat(LlmSummarizer.isNonAnswer("Not enough context to summarize the headline."))
            .isTrue();
        assertThat(LlmSummarizer.isNonAnswer("Please provide the article text to summarize."))
            .isTrue();
        assertThat(LlmSummarizer.isNonAnswer(
            "Unfortunately, there is no information available about this news item.")).isTrue();
        assertThat(LlmSummarizer.isNonAnswer("Too short."))
            .as("shorter than %d characters", LlmSummarizer.MIN_SUMMARY_LENGTH).isTrue();
    }

    @Test
    void does_not_mistake_a_summary_about_missing_things_for_a_refusal() {
        assertThat(LlmSummarizer.isNonAnswer(
            "A chipmaker announced a 2nm process. Production starts next year.")).isFalse();
        assertThat(LlmSummarizer.isNonAnswer(
            "Intel does not plan to close the Dresden plant this year.")).isFalse();
        assertThat(LlmSummarizer.isNonAnswer(
            "There is no evidence the Dresden plant will close, the company said.")).isFalse();
        assertThat(LlmSummarizer.isNonAnswer(
            "No information was released about the pilot's condition, officials said."))
            .isFalse();
        assertThat(LlmSummarizer.isNonAnswer(
            "The text is now law after the president signed it on Monday.")).isFalse();
    }

    @Test
    void the_hourly_budget_is_honoured_when_one_is_set() {
        settings.setMaxCallsPerHour(1);
        OllamaSummarizer limited = new OllamaSummarizer(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            new ExtractiveSummarizer(), emptyCache(), seenArticles(), noContext(), settings);

        limited.summarize(item("First description."));
        String second = limited.summarize(item("Second description here."));

        assertThat(requestBodies).hasSize(1);
        assertThat(second).isEqualTo("Second description here.");
    }

    @Test
    void strips_the_chatter_small_models_like_to_add() {
        assertThat(LlmSummarizer.clean("Here is a summary: The plant opened."))
            .isEqualTo("The plant opened.");
        assertThat(LlmSummarizer.clean("\"The plant opened.\"")).isEqualTo("The plant opened.");
        assertThat(LlmSummarizer.clean("Summary:\nThe plant opened.\n\nIt is large."))
            .isEqualTo("The plant opened. It is large.");
        assertThat(LlmSummarizer.clean("  ")).isEmpty();
    }

    private static SummaryCacheEntity cached(String summary) {
        SummaryCacheEntity entity = new SummaryCacheEntity();
        entity.setSummary(summary);
        return entity;
    }

    @Test
    void builds_the_generate_url_from_the_base_url() {
        settings.setBaseUrl("http://ollama:11434/");

        assertThat(summarizer.generateUrl()).isEqualTo("http://ollama:11434/api/generate");
    }
}
