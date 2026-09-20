package com.dach.sagiri.topic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A forum topic the bot knows about.
 * <p>
 * Bot API offers no way to list the topics of a chat, so this table — not
 * Telegram — is the source of truth about which topics exist and what they are
 * about.
 */
@Entity
@Table(name = "topics")
public class TopicEntity {

    /** Claim marker held while the welcome message is being sent. */
    public static final long WELCOME_IN_PROGRESS = -1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Column(name = "thread_id", nullable = false)
    private long threadId;

    @Column(name = "raw_name", nullable = false)
    private String rawName;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "query", nullable = false)
    private String query;

    /** The subject in words; null until the model has read the slug. */
    @Column(name = "domain")
    private String domain;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_source", nullable = false)
    private TopicProfileSource profileSource = TopicProfileSource.SLUG;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** Telegram's own open/closed state, which a rename must never change. */
    @Column(name = "closed", nullable = false)
    private boolean closed;

    @Column(name = "created_by_bot", nullable = false)
    private boolean createdByBot;

    @Column(name = "welcome_message_id")
    private Long welcomeMessageId;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean hasWelcome() {
        return welcomeMessageId != null && welcomeMessageId != WELCOME_IN_PROGRESS;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getChatId() {
        return chatId;
    }

    public void setChatId(long chatId) {
        this.chatId = chatId;
    }

    public long getThreadId() {
        return threadId;
    }

    public void setThreadId(long threadId) {
        this.threadId = threadId;
    }

    public String getRawName() {
        return rawName;
    }

    public void setRawName(String rawName) {
        this.rawName = rawName;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public TopicProfileSource getProfileSource() {
        return profileSource;
    }

    public void setProfileSource(TopicProfileSource profileSource) {
        this.profileSource = profileSource;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public boolean isCreatedByBot() {
        return createdByBot;
    }

    public void setCreatedByBot(boolean createdByBot) {
        this.createdByBot = createdByBot;
    }

    public Long getWelcomeMessageId() {
        return welcomeMessageId;
    }

    public void setWelcomeMessageId(Long welcomeMessageId) {
        this.welcomeMessageId = welcomeMessageId;
    }

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }

    public void setLastPolledAt(Instant lastPolledAt) {
        this.lastPolledAt = lastPolledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "TopicEntity{id=" + id + ", chatId=" + chatId + ", threadId=" + threadId
               + ", slug='" + slug + "', active=" + active + ", closed=" + closed + '}';
    }
}
