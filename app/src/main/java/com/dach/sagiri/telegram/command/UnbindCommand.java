package com.dach.sagiri.telegram.command;

import org.springframework.stereotype.Component;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicRegistryService;

/** Stops polling the current topic without touching Telegram itself. */
@Component
public class UnbindCommand implements BotCommand {

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final TopicRegistryService registry;

    public UnbindCommand(TelegramSender sender, MessageTemplates messages,
        TopicRegistryService registry) {
        this.sender = sender;
        this.messages = messages;
        this.registry = registry;
    }

    @Override
    public String command() {
        return "/unbind";
    }

    @Override
    public void execute(CommandContext context) {
        if (!context.inTopic()) {
            sender.reply(context.chatId(), context.threadId(), messages.getNotInTopic());
            return;
        }
        boolean changed = registry.setActive(context.chatId(), context.threadId(), false);
        sender.reply(context.chatId(), context.threadId(),
            changed ? messages.getUnbindOk() : messages.getUnbindUnknown());
    }
}
