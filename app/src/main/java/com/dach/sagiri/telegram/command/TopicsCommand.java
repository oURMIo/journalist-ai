package com.dach.sagiri.telegram.command;

import java.util.List;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicRegistryService;

/** Dumps the registry — the bot's only view of which topics exist. */
@Component
public class TopicsCommand implements BotCommand {

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final TopicRegistryService registry;

    public TopicsCommand(TelegramSender sender, MessageTemplates messages,
        TopicRegistryService registry) {
        this.sender = sender;
        this.messages = messages;
        this.registry = registry;
    }

    @Override
    public String command() {
        return "/topics";
    }

    @Override
    public void execute(CommandContext context) {
        List<TopicEntity> topics = registry.allTopics().stream()
            .filter(topic -> topic.getChatId() == context.chatId())
            .toList();

        if (topics.isEmpty()) {
            sender.reply(context.chatId(), context.threadId(), messages.getTopicsEmpty());
            return;
        }

        StringBuilder out = new StringBuilder("<b>Topic registry</b>\n");
        for (TopicEntity topic : topics) {
            out.append(marker(topic))
                .append(" <code>").append(HtmlText.escape(topic.getSlug())).append("</code>")
                .append(" — thread ").append(topic.getThreadId())
                .append(topic.isCreatedByBot() ? " (created by the bot)" : "")
                .append('\n');
        }
        sender.reply(context.chatId(), context.threadId(), out.toString().strip());
    }

    /** 🔒 closed on Telegram, 🟢 polled, ⚪ known but not ours. */
    private static String marker(TopicEntity topic) {
        if (topic.isClosed()) {
            return "🔒";
        }
        return topic.isActive() ? "🟢" : "⚪";
    }
}
