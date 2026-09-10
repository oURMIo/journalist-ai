package com.dach.sagiri.telegram.command;

import java.util.Map;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.schedule.NewsPollingJob;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicRegistryService;

/** Polls the current topic right now instead of waiting for the next cycle. */
@Component
public class NowCommand implements BotCommand {

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final TopicRegistryService registry;
    private final NewsPollingJob pollingJob;

    public NowCommand(TelegramSender sender, MessageTemplates messages,
        TopicRegistryService registry, NewsPollingJob pollingJob) {
        this.sender = sender;
        this.messages = messages;
        this.registry = registry;
        this.pollingJob = pollingJob;
    }

    @Override
    public String command() {
        return "/now";
    }

    @Override
    public void execute(CommandContext context) {
        if (!context.inTopic()) {
            sender.reply(context.chatId(), context.threadId(), messages.getNotInTopic());
            return;
        }
        registry.find(context.chatId(), context.threadId())
            .filter(topic -> topic.isActive())
            .ifPresentOrElse(topic -> {
                sender.reply(context.chatId(), context.threadId(),
                    MessageTemplates.render(messages.getNowStarted(),
                        Map.of("slug", HtmlText.escape(topic.getSlug()))));
                int sent = pollingJob.pollTopic(topic);
                sender.reply(context.chatId(), context.threadId(),
                    MessageTemplates.render(messages.getNowDone(), Map.of(
                        "slug", HtmlText.escape(topic.getSlug()),
                        "sent", sent)));
            }, () -> sender.reply(context.chatId(), context.threadId(),
                messages.getUnbindUnknown()));
    }
}
