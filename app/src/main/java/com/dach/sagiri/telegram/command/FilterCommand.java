package com.dach.sagiri.telegram.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.filter.AiRelevanceChecker;
import com.dach.sagiri.filter.FilterKind;
import com.dach.sagiri.filter.TopicFilterService;
import com.dach.sagiri.filter.TopicFilterService.AddResult;
import com.dach.sagiri.filter.TopicFilters;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicRegistryService;

/**
 * Edits the keyword rules of the topic the command was sent from:
 * {@code /exclude go}, {@code /include kubernetes}, {@code /filter remove go},
 * {@code /filter clear}, and {@code /filter} alone to see them.
 */
@Component
public class FilterCommand implements BotCommand {

    private static final String EXCLUDE = "exclude";
    private static final String INCLUDE = "include";
    private static final String REMOVE = "remove";
    private static final String CLEAR = "clear";

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final BotProperties properties;
    private final TopicRegistryService registry;
    private final TopicFilterService filters;
    private final AiRelevanceChecker ai;

    public FilterCommand(TelegramSender sender, MessageTemplates messages,
        BotProperties properties, TopicRegistryService registry, TopicFilterService filters,
        AiRelevanceChecker ai) {
        this.sender = sender;
        this.messages = messages;
        this.properties = properties;
        this.registry = registry;
        this.filters = filters;
        this.ai = ai;
    }

    @Override
    public String command() {
        return "/filter";
    }

    @Override
    public List<String> aliases() {
        return List.of("/filters", "/exclude", "/include");
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
        String command = context.command();
        if ("/exclude".equals(command) || "/include".equals(command)) {
            add(context, topic, "/exclude".equals(command) ? FilterKind.EXCLUDE
                : FilterKind.INCLUDE, join(context.args()));
            return;
        }
        if (context.args().isEmpty()) {
            reply(context, list(topic));
            return;
        }

        String subcommand = context.args().getFirst().toLowerCase(Locale.ROOT);
        String rest = join(context.args().subList(1, context.args().size()));
        switch (subcommand) {
            case EXCLUDE -> add(context, topic, FilterKind.EXCLUDE, rest);
            case INCLUDE -> add(context, topic, FilterKind.INCLUDE, rest);
            case REMOVE -> remove(context, topic, rest);
            case CLEAR -> reply(context, MessageTemplates.render(messages.getFilterCleared(),
                Map.of("count", filters.clear(topic))));
            default -> reply(context, messages.getFilterUsage());
        }
    }

    private void add(CommandContext context, TopicEntity topic, FilterKind kind, String terms) {
        if (TopicFilterService.parseTerms(terms).isEmpty()) {
            reply(context, messages.getFilterUsage());
            return;
        }
        AddResult result = filters.add(topic, kind, terms);
        if (result.added().isEmpty()) {
            reply(context, result.limitReached() ? limitReached()
                : messages.getFilterNothingAdded());
            return;
        }
        String added = MessageTemplates.render(messages.getFilterAdded(), Map.of(
            "kind", kind == FilterKind.EXCLUDE
                ? messages.getFilterKindExclude() : messages.getFilterKindInclude(),
            "terms", code(result.added())));
        // Part of the batch may have been dropped, and silence would read as success.
        String note = result.limitReached() ? "\n" + limitReached() : "";
        reply(context, added + note + "\n\n" + list(topic));
    }

    private String limitReached() {
        return MessageTemplates.render(messages.getFilterLimit(),
            Map.of("max", properties.getFilter().getMaxTermsPerTopic()));
    }

    private void remove(CommandContext context, TopicEntity topic, String terms) {
        if (TopicFilterService.parseTerms(terms).isEmpty()) {
            reply(context, messages.getFilterUsage());
            return;
        }
        List<String> removed = filters.remove(topic, terms);
        if (removed.isEmpty()) {
            reply(context, MessageTemplates.render(messages.getFilterNotFound(),
                Map.of("terms", code(TopicFilterService.parseTerms(terms)))));
            return;
        }
        reply(context, MessageTemplates.render(messages.getFilterRemoved(),
            Map.of("terms", code(removed))) + "\n\n" + list(topic));
    }

    private String list(TopicEntity topic) {
        TopicFilters rules = filters.of(topic);
        String slug = HtmlText.escape(topic.getSlug());
        if (rules.isEmpty()) {
            return MessageTemplates.render(messages.getFilterEmpty(), Map.of("slug", slug))
                   + "\n\n" + messages.getFilterUsage();
        }

        StringBuilder out = new StringBuilder(
            MessageTemplates.render(messages.getFilterHeader(), Map.of("slug", slug)));
        if (!rules.exclude().isEmpty()) {
            out.append('\n').append(messages.getFilterExcludeLabel()).append(' ')
                .append(code(rules.exclude()));
        }
        if (!rules.include().isEmpty()) {
            out.append('\n').append(messages.getFilterIncludeLabel()).append(' ')
                .append(code(rules.include()));
        }
        return out.append('\n')
            .append(ai.available() ? messages.getFilterAiOn() : messages.getFilterAiOff())
            .toString();
    }

    private static String code(List<String> terms) {
        return terms.stream()
            .map(term -> "<code>" + HtmlText.escape(term) + "</code>")
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
    }

    private static String join(List<String> args) {
        return String.join(" ", args);
    }

    private void reply(CommandContext context, String text) {
        sender.reply(context.chatId(), context.threadId(), text);
    }
}
