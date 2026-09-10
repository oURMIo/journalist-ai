package com.dach.sagiri.topic;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface TopicRepository extends JpaRepository<TopicEntity, Long> {

    Optional<TopicEntity> findByChatIdAndThreadId(long chatId, long threadId);

    Optional<TopicEntity> findByChatIdAndSlugAndActiveTrue(long chatId, String slug);

    List<TopicEntity> findByActiveTrueOrderByChatIdAscThreadIdAsc();

    List<TopicEntity> findAllByOrderByChatIdAscThreadIdAsc();

    /**
     * Atomically takes ownership of sending the welcome message. Exactly one
     * caller gets {@code 1} back, which is what keeps a bot-created topic from
     * being greeted twice when the {@code forum_topic_created} update arrives.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TopicEntity t set t.welcomeMessageId = :marker, t.updatedAt = :now "
           + "where t.id = :id and t.welcomeMessageId is null")
    int claimWelcome(@Param("id") Long id, @Param("marker") long marker,
        @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TopicEntity t set t.welcomeMessageId = :messageId, t.updatedAt = :now "
           + "where t.id = :id")
    int setWelcomeMessageId(@Param("id") Long id, @Param("messageId") Long messageId,
        @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TopicEntity t set t.lastPolledAt = :polledAt, t.updatedAt = :polledAt "
           + "where t.id = :id")
    int markPolled(@Param("id") Long id, @Param("polledAt") Instant polledAt);
}
