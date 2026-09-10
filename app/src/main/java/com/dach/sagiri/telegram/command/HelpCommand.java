package com.dach.sagiri.telegram.command;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.telegram.TelegramSender;

/** {@code /start} and {@code /help}: the only commands open to everyone. */
@Component
public class HelpCommand implements BotCommand {

    private final TelegramSender sender;
    private final MessageTemplates messages;
    private final BotProperties properties;

    public HelpCommand(TelegramSender sender, MessageTemplates messages,
        BotProperties properties) {
        this.sender = sender;
        this.messages = messages;
        this.properties = properties;
    }

    @Override
    public String command() {
        return "/help";
    }

    @Override
    public List<String> aliases() {
        return List.of("/start");
    }

    @Override
    public boolean adminOnly() {
        return false;
    }

    @Override
    public void execute(CommandContext context) {
        String text = MessageTemplates.render(messages.getHelp(),
            Map.of("prefix", properties.getTopicPrefix()));
        // Answers in the very topic the command came from, never in General.
        sender.reply(context.chatId(), context.threadId(), text);
    }
}
