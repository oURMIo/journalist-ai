package com.dach.sagiri.telegram.command;

import org.springframework.stereotype.Component;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicProvisioningService;

/** Closes the current forum topic and deactivates it in the registry. */
@Component
public class CloseTopicCommand implements BotCommand {

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final TopicProvisioningService provisioning;

    public CloseTopicCommand(TelegramSender sender, MessageTemplates messages,
        TopicProvisioningService provisioning) {
        this.sender = sender;
        this.messages = messages;
        this.provisioning = provisioning;
    }

    @Override
    public String command() {
        return "/closetopic";
    }

    @Override
    public void execute(CommandContext context) {
        if (!context.inTopic()) {
            sender.reply(context.chatId(), context.threadId(), messages.getNotInTopic());
            return;
        }
        // The confirmation goes out first: once the topic is closed nothing can be
        // posted into it any more.
        sender.reply(context.chatId(), context.threadId(), messages.getClosetopicOk());
        provisioning.close(context.chatId(), context.threadId());
    }
}
