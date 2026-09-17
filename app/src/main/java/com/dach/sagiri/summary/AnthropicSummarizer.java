package com.dach.sagiri.summary;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.dedup.SeenArticleService;

/**
 * Hosted alternative to {@link OllamaSummarizer}, enabled with
 * {@code SUMMARIZER=anthropic}. Costs money per article, so it is opt-in.
 */
public class AnthropicSummarizer extends LlmSummarizer {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicSummarizer.class);

    private final AnthropicClient client;
    private final BotProperties.Llm settings;

    public AnthropicSummarizer(AnthropicClient client, ExtractiveSummarizer fallback,
        SummaryCacheRepository cache, SeenArticleService seenArticles,
        BotProperties.Llm settings) {

        super(fallback, cache, seenArticles, settings.getMaxCallsPerHour());
        this.client = client;
        this.settings = settings;
    }

    @Override
    protected String backend() {
        return "Anthropic(" + settings.getModel() + ")";
    }

    @Override
    protected String generate(String prompt, String label) {
        MessageCreateParams params = MessageCreateParams.builder()
            .model(settings.getModel())
            .maxTokens(settings.getMaxTokens())
            // A two-sentence recap needs no deliberation; keep the cost down.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(prompt)
            .build();

        try {
            Message response = client.messages().create(params);
            return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .reduce("", (left, right) -> left + right)
                .strip();
        } catch (RuntimeException e) {
            logger.warn("Anthropic summarisation failed for '{}': {}", label, e.toString());
            return "";
        }
    }
}
