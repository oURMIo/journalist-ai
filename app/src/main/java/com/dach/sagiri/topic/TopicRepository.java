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

    List<TopicEntity> findByActiveTrueAndClosedFalseOrderByChatIdAscThreadIdAsc();

    List<TopicEntity> findAllByOrderByChatIdAscThreadIdAsc();

    boolean existsByIdAndActiveTrueAndClosedFalse(Long id);

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

    /**
     * Stores what the model made of the slug, but only while nothing better is
     * there. Two cycles - the scheduler and a {@code /now} - can derive the same
     * topic at once, and a hand-written query must survive both.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TopicEntity t set t.domain = :domain, t.query = :query, "
           + "t.profileSource = com.dach.sagiri.topic.TopicProfileSource.MODEL, "
           + "t.updatedAt = :now where t.id = :id and t.profileSource = "
           + "com.dach.sagiri.topic.TopicProfileSource.SLUG")
    int applyDerivedProfile(@Param("id") Long id, @Param("domain") String domain,
        @Param("query") String query, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TopicEntity t set t.domain = :domain, t.query = :query, "
           + "t.profileSource = :source, t.updatedAt = :now where t.id = :id")
    int setProfile(@Param("id") Long id, @Param("domain") String domain,
        @Param("query") String query, @Param("source") TopicProfileSource source,
        @Param("now") Instant now);
}
