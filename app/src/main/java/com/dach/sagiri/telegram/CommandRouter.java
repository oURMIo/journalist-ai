package com.dach.sagiri.telegram;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.telegram.command.BotCommand;
import com.dach.sagiri.telegram.command.CommandContext;
import com.pengrad.telegrambot.model.Message;

/**
 * Resolves the command word of an incoming message and runs the matching handler.
 * Grew out of the skeleton's {@code CommandService}; the lookup-by-first-word
 * behaviour is unchanged, the routing rules around it are new.
 */
@Component
public class CommandRouter {

    private static final Logger logger = LoggerFactory.getLogger(CommandRouter.class);

    private final Map<String, BotCommand> commands = new HashMap<>();
    private final BotProperties properties;
    private final MessageTemplates messages;
    private final TelegramSender sender;

    public CommandRouter(List<BotCommand> commands, BotProperties properties,
        MessageTemplates messages, TelegramSender sender) {
        this.properties = properties;
        this.messages = messages;
        this.sender = sender;
        for (BotCommand command : commands) {
            register(command.command(), command);
            command.aliases().forEach(alias -> register(alias, command));
        }
        logger.info("Registered {} bot command(s): {}", this.commands.size(),
            this.commands.keySet());
    }

    private void register(String name, BotCommand command) {
        commands.put(name.toLowerCase(Locale.ROOT), command);
    }

    /** @return {@code true} when the message was a known command and got handled */
    public boolean dispatch(Message message) {
        String text = StringUtils.trimToEmpty(message.text());
        if (!text.startsWith("/")) {
            return false;
        }
        String[] parts = text.split("\\s+");
        String name = stripBotMention(parts[0]).toLowerCase(Locale.ROOT);

        BotCommand command = commands.get(name);
        if (command == null) {
            // An unknown command is silently ignored: the bot shares the group with
            // other bots and must not spam on every stray slash.
            logger.debug("Ignoring unknown command '{}'", name);
            return false;
        }

        long chatId = message.chat().id();
        Long threadId = threadIdOf(message);
        Long userId = message.from() == null ? null : message.from().id();
        boolean admin = properties.isAdmin(userId);

        if (command.adminOnly() && !admin) {
            sender.reply(chatId, threadId, messages.getNotAdmin());
            return true;
        }

        List<String> args = Arrays.stream(parts).skip(1).filter(StringUtils::isNotBlank).toList();
        command.execute(new CommandContext(message, chatId, threadId, name, args, admin));
        return true;
    }

    /** {@code /status@my_bot} in a group must resolve to {@code /status}. */
    private String stripBotMention(String token) {
        int at = token.indexOf('@');
        return at < 0 ? token : token.substring(0, at);
    }

    /**
     * The General topic carries no {@code message_thread_id}; in every other topic
     * Telegram sets {@code is_topic_message}. Reply threads of non-forum groups
     * also set a thread id, hence the extra flag check.
     */
    public static Long threadIdOf(Message message) {
        return Boolean.TRUE.equals(message.isTopicMessage()) ? message.messageThreadId() : null;
    }

    /** Lookup by name, used by tests and by the help listing. */
    public Optional<BotCommand> find(String name) {
        return Optional.ofNullable(commands.get(name.toLowerCase(Locale.ROOT)));
    }
}
