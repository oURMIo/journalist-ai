package com.dach.sagiri.telegram.command;

import java.util.Map;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.config.TopicPresetsConfig;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicRegistryService;

/** Lists the curated presets and marks the ones already live in this chat. */
@Component
public class PresetsCommand implements BotCommand {

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final TopicPresetsConfig presets;
    private final TopicRegistryService registry;

    public PresetsCommand(TelegramSender sender, MessageTemplates messages,
        TopicPresetsConfig presets, TopicRegistryService registry) {
        this.sender = sender;
        this.messages = messages;
        this.presets = presets;
        this.registry = registry;
    }

    @Override
    public String command() {
        return "/presets";
    }

    @Override
    public void execute(CommandContext context) {
        StringBuilder out = new StringBuilder(messages.getPresetsHeader()).append('\n');
        for (Map.Entry<String, TopicPresetsConfig.Preset> entry
            : presets.getPresets().entrySet()) {

            boolean live = registry.findActiveBySlug(context.chatId(), entry.getKey()).isPresent();
            out.append(live ? "✅" : "▫️")
                .append(" <code>").append(HtmlText.escape(entry.getKey())).append("</code> — ")
                .append(HtmlText.escape(entry.getValue().getQuery()))
                .append('\n');
        }
        out.append('\n').append(messages.getPresetsFallbackNote());
        sender.reply(context.chatId(), context.threadId(), out.toString().strip());
    }
}
