package com.dach.sagiri.telegram.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicProfileService;
import com.dach.sagiri.topic.TopicProfileSource;
import com.dach.sagiri.topic.TopicProfiler;
import com.dach.sagiri.topic.TopicQueryResolver;
import com.dach.sagiri.topic.TopicRegistryService;

/**
 * Shows what the bot decided the topic is about and lets the reader overrule it:
 * {@code /query}, {@code /query "java programming" OR jvm}, {@code /query auto}.
 * <p>
 * This is the escape hatch for a subject the model read wrong — and the only
 * place where the search query of one topic is visible at all.
 */
@Component
public class QueryCommand implements BotCommand {

    private static final List<String> RESET_WORDS = List.of("auto", "reset", "авто", "сброс");

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final TopicRegistryService registry;
    private final TopicQueryResolver queryResolver;
    private final TopicProfileService profiles;

    public QueryCommand(TelegramSender sender, MessageTemplates messages,
        TopicRegistryService registry, TopicQueryResolver queryResolver,
        TopicProfileService profiles) {
        this.sender = sender;
        this.messages = messages;
        this.registry = registry;
        this.queryResolver = queryResolver;
        this.profiles = profiles;
    }

    @Override
    public String command() {
        return "/query";
    }

    @Override
    public void execute(CommandContext context) {
        if (!context.inTopic()) {
            reply(context, messages.getNotInTopic());
            return;
        }
        registry.find(context.chatId(), context.threadId()).ifPresentOrElse(
            topic -> run(context, topic),
            () -> reply(context, messages.getUnbindUnknown()));
    }

    private void run(CommandContext context, TopicEntity topic) {
        if (context.args().isEmpty()) {
            reply(context, show(topic) + "\n\n" + messages.getQueryUsage());
            return;
        }
        if (context.args().size() == 1
            && RESET_WORDS.contains(context.args().getFirst().toLowerCase(Locale.ROOT))) {
            profiles.resetQuery(topic);
            reply(context, messages.getQueryReset() + "\n\n" + show(topic));
            return;
        }

        String query = TopicProfiler.sanitizeQuery(String.join(" ", context.args()));
        if (query.isEmpty()) {
            reply(context, messages.getQueryUsage());
            return;
        }
        profiles.setQuery(topic, query);
        reply(context, MessageTemplates.render(messages.getQuerySet(),
            Map.of("query", HtmlText.escape(query))) + "\n\n" + show(topic));
    }

    /** Reads the stored profile only: a command reply must not wait for a model call. */
    private String show(TopicEntity topic) {
        TopicQuery resolved = queryResolver.resolve(topic);
        String header = MessageTemplates.render(messages.getQueryHeader(), Map.of(
            "slug", HtmlText.escape(topic.getSlug()),
            "domain", domain(topic),
            "query", HtmlText.escape(resolved.query()),
            "source", source(topic, resolved)));
        if (resolved.feeds().isEmpty()) {
            return header;
        }
        return header + "\n" + MessageTemplates.render(messages.getQueryFeeds(),
            Map.of("count", resolved.feeds().size()));
    }

    /** A topic whose query was written by hand is never read by the model again. */
    private String domain(TopicEntity topic) {
        if (topic.getDomain() != null && !topic.getDomain().isBlank()) {
            return HtmlText.escape(topic.getDomain());
        }
        return topic.getProfileSource() == TopicProfileSource.USER
            ? messages.getQueryDomainDropped()
            : messages.getQueryDomainUnknown();
    }

    /** Mirrors the order {@link TopicQueryResolver} resolves a topic in. */
    private String source(TopicEntity topic, TopicQuery resolved) {
        if (topic.getProfileSource() == TopicProfileSource.USER) {
            return messages.getQuerySourceUser();
        }
        if (resolved.preset()) {
            return messages.getQuerySourcePreset();
        }
        return topic.getProfileSource() == TopicProfileSource.MODEL
            ? messages.getQuerySourceModel()
            : messages.getQuerySourceSlug();
    }

    private void reply(CommandContext context, String text) {
        sender.reply(context.chatId(), context.threadId(), text);
    }
}
