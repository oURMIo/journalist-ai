-- Topic registry. Bot API has no `getForumTopics`, so this table is the single
-- source of truth about which forum topics exist and what they are about.
create table topics
(
    id                 bigserial primary key,
    chat_id            bigint      not null,
    thread_id          bigint      not null,
    raw_name           text        not null,
    slug               text        not null,
    query              text        not null,
    active             boolean     not null default true,
    created_by_bot     boolean     not null default false,
    welcome_message_id bigint,
    last_polled_at     timestamptz,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

-- Upsert key: one row per (chat, thread). Guards against the duplicate
-- `forum_topic_created` update that Telegram sends after `createForumTopic`.
create unique index ux_topics_chat_thread on topics (chat_id, thread_id);

-- At most one ACTIVE topic per subject in a chat.
create unique index ux_topics_chat_slug_active on topics (chat_id, slug) where active;

create index ix_topics_active on topics (active) where active;

-- Articles already published into a topic, used for cross-run de-duplication.
create table seen_articles
(
    id          bigserial primary key,
    topic_id    bigint      not null references topics (id) on delete cascade,
    fingerprint text        not null,
    title_hash  text        not null,
    url         text        not null,
    sent_at     timestamptz not null default now()
);

create unique index ux_seen_topic_fingerprint on seen_articles (topic_id, fingerprint);

-- The same story arrives from different aggregators under different URLs,
-- so the normalised title is a second de-duplication axis.
create index ix_seen_topic_title_hash on seen_articles (topic_id, title_hash);

create index ix_seen_sent_at on seen_articles (sent_at);

-- Cache of generated summaries so an LLM is never paid twice for one article.
create table summary_cache
(
    fingerprint text primary key,
    summary     text        not null,
    created_at  timestamptz not null default now()
);
