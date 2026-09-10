package com.dach.sagiri.telegram.command;

import java.util.List;

/**
 * A command the bot answers to. Implementations are picked up by
 * {@link com.dach.sagiri.telegram.CommandRouter} through Spring.
 * <p>
 * Kept from the original skeleton, with the execution context replacing the raw
 * {@code (bot, message)} pair so that every reply lands in the right topic.
 */
public interface BotCommand {

    /** Primary name, including the leading slash, e.g. {@code "/status"}. */
    String command();

    /** Extra names answering to the same handler. */
    default List<String> aliases() {
        return List.of();
    }

    /** Admin-only by default: only /start and /help are open to everyone. */
    default boolean adminOnly() {
        return true;
    }

    void execute(CommandContext context);
}
