package com.dach.sagiri.topic;

import java.util.List;

/**
 * The one place where topics are created, renamed and closed by the bot.
 * <p>
 * Creating a topic through {@code createForumTopic} is more reliable than asking
 * a human to do it: the response carries the {@code message_thread_id}, so the
 * registry is filled immediately instead of racing the update stream.
 */
public interface TopicProvisioningService {

    /** Creates one news topic for a subject slug ({@code "it"} to {@code "news-it"}). */
    ProvisionResult provision(long chatId, String slug);

    /** Batch creation. Never stops at the first failure. */
    List<ProvisionResult> provisionAll(long chatId, List<String> slugs);

    /** Renames an existing topic and syncs the registry and the welcome message. */
    ProvisionResult rename(long chatId, long threadId, String newSlug);

    /** Closes a topic and deactivates it in the registry. */
    void close(long chatId, long threadId);
}
