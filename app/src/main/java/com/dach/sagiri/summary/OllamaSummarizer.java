package com.dach.sagiri.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.dedup.SeenArticleService;
import com.dach.sagiri.news.ArticleContextFetcher;

/**
 * Summariser backed by an Ollama server running next to the bot — a local model,
 * no API key, no per-article cost, and nothing leaves the compose network.
 * <p>
 * Talks to {@code POST /api/generate} with streaming off. The model may not be
 * pulled yet right after the stack comes up; that is reported clearly and the
 * extractive summariser covers the gap.
 */
public class OllamaSummarizer extends LlmSummarizer {

    private static final Logger logger = LoggerFactory.getLogger(OllamaSummarizer.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client;
    private final BotProperties.Ollama settings;

    public OllamaSummarizer(HttpClient client, ExtractiveSummarizer fallback,
        SummaryCacheRepository cache, SeenArticleService seenArticles,
        ArticleContextFetcher articles, BotProperties.Ollama settings) {

        super(fallback, cache, seenArticles, articles, settings.getMaxCallsPerHour());
        this.client = client;
        this.settings = settings;
    }

    @Override
    protected String backend() {
        return "Ollama(" + settings.getModel() + ")";
    }

    @Override
    protected String generate(String prompt, String label) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(generateUrl()))
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(prompt), StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 404) {
                // Ollama answers 404 until `ollama pull` has finished.
                logger.warn("Model '{}' is not available on the Ollama server yet ({}). "
                            + "Falling back until the pull completes.",
                    settings.getModel(), StringUtils.abbreviate(response.body(), 200));
                return "";
            }
            if (response.statusCode() != 200) {
                logger.warn("Ollama returned {} for '{}': {}", response.statusCode(),
                    label, StringUtils.abbreviate(response.body(), 200));
                return "";
            }
            return readSummary(response.body());
        } catch (IOException e) {
            logger.warn("Ollama is unreachable at {} ({}), using the extractive summariser",
                settings.getBaseUrl(), e.toString());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (RuntimeException e) {
            logger.warn("Ollama summarisation failed for '{}': {}", label, e.toString());
            return "";
        }
    }

    String generateUrl() {
        return StringUtils.removeEnd(settings.getBaseUrl(), "/") + "/api/generate";
    }

    String body(String prompt) {
        ObjectNode options = JSON.createObjectNode()
            // Low temperature: this is a factual recap, not creative writing.
            .put("temperature", settings.getTemperature())
            .put("num_predict", settings.getNumPredict());

        ObjectNode root = JSON.createObjectNode()
            .put("model", settings.getModel())
            .put("prompt", prompt)
            .put("stream", false);
        root.set("options", options);
        return root.toString();
    }

    private static String readSummary(String payload) {
        try {
            JsonNode node = JSON.readTree(payload);
            return node.path("response").asText("");
        } catch (IOException e) {
            logger.warn("Could not read the Ollama response: {}", e.toString());
            return "";
        }
    }
}
