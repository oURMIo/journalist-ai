package com.dach.sagiri.telegram.command;

import java.util.List;
import java.util.Optional;
import com.pengrad.telegrambot.model.Message;

/**
 * Everything a command handler needs about the message that triggered it.
 *
 * @param threadId forum topic the command came from; {@code null} for General
 * @param args     arguments after the command word
 * @param admin    whether the sender may run admin-only commands
 */
public record CommandContext(Message message, long chatId, Long threadId, String command,
                             List<String> args, boolean admin) {

    public boolean inTopic() {
        return threadId != null;
    }

    public Optional<String> firstArg() {
        return args.isEmpty() ? Optional.empty() : Optional.of(args.getFirst());
    }

    public Long userId() {
        return message.from() == null ? null : message.from().id();
    }
}
