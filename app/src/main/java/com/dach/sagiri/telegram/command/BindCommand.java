package com.dach.sagiri.telegram.command;

import java.util.Map;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicRegistryService;
import com.dach.sagiri.topic.TopicWelcomeService;

/**
 * Attaches a topic that existed before the bot joined the group. Such topics are
 * invisible to the bot — Bot API cannot list them and no creation update was ever
 * delivered — so the name has to be supplied by hand.
 */
@Component
public class BindCommand implements BotCommand {

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final BotProperties properties;
    private final TopicRegistryService registry;
    private final TopicWelcomeService welcomeService;

    public BindCommand(TelegramSender sender, MessageTemplates messages,
        BotProperties properties, TopicRegistryService registry,
        TopicWelcomeService welcomeService) {
        this.sender = sender;
        this.messages = messages;
        this.properties = properties;
        this.registry = registry;
        this.welcomeService = welcomeService;
    }

    @Override
    public String command() {
        return "/bind";
    }

    @Override
    public void execute(CommandContext context) {
        if (!context.inTopic()) {
            sender.reply(context.chatId(), context.threadId(), messages.getNotInTopic());
            return;
        }
        if (context.args().isEmpty()) {
            sender.reply(context.chatId(), context.threadId(),
                MessageTemplates.render(messages.getBindUsage(),
                    Map.of("prefix", properties.getTopicPrefix())));
            return;
        }

        String name = String.join(" ", context.args());
        registry.bind(context.chatId(), context.threadId(), name).ifPresentOrElse(
            topic -> {
                welcomeService.ensureWelcome(topic);
                sender.reply(context.chatId(), context.threadId(),
                    MessageTemplates.render(messages.getBindOk(), Map.of(
                        "name", HtmlText.escape(topic.getRawName()),
                        "slug", HtmlText.escape(topic.getSlug()))));
            },
            () -> sender.reply(context.chatId(), context.threadId(),
                MessageTemplates.render(messages.getBindNotNews(), Map.of(
                    "name", HtmlText.escape(name),
                    "prefix", properties.getTopicPrefix()))));
    }
}
