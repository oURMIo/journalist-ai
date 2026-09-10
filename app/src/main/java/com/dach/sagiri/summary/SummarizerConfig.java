package com.dach.sagiri.summary;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.dedup.SeenArticleService;

/**
 * Picks the summariser from {@code SUMMARIZER}.
 * <ul>
 *   <li>{@code ollama} — the local model started by docker compose (default in Docker);</li>
 *   <li>{@code anthropic} (alias {@code llm}) — hosted Claude, needs a key and costs money;</li>
 *   <li>{@code extractive} — no model at all, first sentences of the feed description.</li>
 * </ul>
 * A misconfigured choice degrades to extractive with a warning rather than
 * preventing the application from starting.
 */
@Configuration
public class SummarizerConfig {

    private static final Logger logger = LoggerFactory.getLogger(SummarizerConfig.class);

    static final String OLLAMA = "ollama";
    static final String ANTHROPIC = "anthropic";
    /** Kept because the spec names the mode {@code llm}. */
    static final String LLM_ALIAS = "llm";

    @Bean
    public ExtractiveSummarizer extractiveSummarizer() {
        return new ExtractiveSummarizer();
    }

    @Bean
    public Summarizer summarizer(BotProperties properties, ExtractiveSummarizer extractive,
        SummaryCacheRepository cache, SeenArticleService seenArticles, HttpClient newsHttpClient) {

        String choice = StringUtils.lowerCase(StringUtils.trimToEmpty(properties.getSummarizer()),
            Locale.ROOT);

        return switch (choice) {
            case OLLAMA -> {
                BotProperties.Ollama settings = properties.getOllama();
                logger.info("Summaries come from Ollama: model {} at {}", settings.getModel(),
                    settings.getBaseUrl());
                yield new OllamaSummarizer(newsHttpClient, extractive, cache, seenArticles,
                    settings);
            }
            case ANTHROPIC, LLM_ALIAS -> {
                BotProperties.Llm settings = properties.getLlm();
                if (StringUtils.isBlank(settings.getApiKey())) {
                    logger.warn("SUMMARIZER={} but LLM_API_KEY is empty, falling back to the "
                                + "extractive summariser", choice);
                    yield extractive;
                }
                logger.info("Summaries come from Anthropic: model {}", settings.getModel());
                yield new AnthropicSummarizer(anthropicClient(settings), extractive, cache,
                    seenArticles, settings);
            }
            default -> {
                logger.info("Summaries come from the extractive summariser");
                yield extractive;
            }
        };
    }

    private static AnthropicClient anthropicClient(BotProperties.Llm settings) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
            .apiKey(settings.getApiKey())
            .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
            // The spec allows one retry at most before falling back.
            .maxRetries(1);
        if (StringUtils.isNotBlank(settings.getBaseUrl())) {
            builder.baseUrl(settings.getBaseUrl());
        }
        return builder.build();
    }
}
