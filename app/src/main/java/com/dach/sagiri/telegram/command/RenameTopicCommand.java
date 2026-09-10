package com.dach.sagiri.telegram.command;

import org.springframework.stereotype.Component;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.ProvisionResult;
import com.dach.sagiri.topic.TopicProvisioningService;

/** Renames the current topic and keeps the registry and welcome message in sync. */
@Component
public class RenameTopicCommand implements BotCommand {

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final TopicProvisioningService provisioning;

    public RenameTopicCommand(TelegramSender sender, MessageTemplates messages,
        TopicProvisioningService provisioning) {
        this.sender = sender;
        this.messages = messages;
        this.provisioning = provisioning;
    }

    @Override
    public String command() {
        return "/renametopic";
    }

    @Override
    public void execute(CommandContext context) {
        if (!context.inTopic()) {
            sender.reply(context.chatId(), context.threadId(), messages.getNotInTopic());
            return;
        }
        if (context.args().isEmpty()) {
            sender.reply(context.chatId(), context.threadId(), messages.getRenameUsage());
            return;
        }
        ProvisionResult result = provisioning.rename(context.chatId(), context.threadId(),
            context.args().getFirst());
        sender.reply(context.chatId(), context.threadId(), HtmlText.escape(result.message()));
    }
}
