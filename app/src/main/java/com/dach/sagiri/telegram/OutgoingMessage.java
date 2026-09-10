package com.dach.sagiri.telegram;

/**
 * One queued outbound message.
 *
 * @param chatId   target supergroup
 * @param threadId forum topic; {@code null} means the General topic
 * @param html     body, already escaped and formatted
 */
public record OutgoingMessage(long chatId, Long threadId, String html, boolean linkPreview,
                              boolean silent) {

    public static OutgoingMessage reply(long chatId, Long threadId, String html) {
        return new OutgoingMessage(chatId, threadId, html, false, false);
    }
}
