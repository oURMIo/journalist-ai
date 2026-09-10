package com.dach.sagiri.telegram.command;

import java.util.List;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.ProvisionResult;
import com.dach.sagiri.topic.TopicProvisioningService;

/**
 * {@code /newtopic <subject>} and {@code /newtopics <s1> <s2> …}.
 * <p>
 * Both go through the one provisioning service — there is no second creation
 * path in the command layer.
 */
@Component
public class NewTopicCommand implements BotCommand {

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final TopicProvisioningService provisioning;

    public NewTopicCommand(TelegramSender sender, MessageTemplates messages,
        TopicProvisioningService provisioning) {
        this.sender = sender;
        this.messages = messages;
        this.provisioning = provisioning;
    }

    @Override
    public String command() {
        return "/newtopic";
    }

    @Override
    public List<String> aliases() {
        return List.of("/newtopics");
    }

    @Override
    public void execute(CommandContext context) {
        boolean batch = "/newtopics".equals(context.command());
        if (context.args().isEmpty()) {
            sender.reply(context.chatId(), context.threadId(),
                batch ? messages.getNewtopicsUsage() : messages.getNewtopicUsage());
            return;
        }

        List<String> slugs = batch ? context.args() : List.of(context.args().getFirst());
        List<ProvisionResult> results = provisioning.provisionAll(context.chatId(), slugs);
        sender.reply(context.chatId(), context.threadId(), summarize(results));
    }

    /** One summary message instead of a burst: the batch already costs chat quota. */
    private static String summarize(List<ProvisionResult> results) {
        StringBuilder out = new StringBuilder();
        for (ProvisionResult result : results) {
            out.append(icon(result.status())).append(' ')
                .append(HtmlText.escape(result.message())).append('\n');
        }
        return out.toString().strip();
    }

    private static String icon(ProvisionResult.Status status) {
        return switch (status) {
            case CREATED -> "✅";
            case ALREADY_EXISTS -> "ℹ️";
            case INVALID_SLUG -> "⚠️";
            case FAILED -> "❌";
        };
    }
}
