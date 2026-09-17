-- Per-topic keyword rules. A topic without rows here behaves exactly as before.
create table topic_filters
(
    id         bigserial primary key,
    topic_id   bigint      not null references topics (id) on delete cascade,
    kind       text        not null,
    term       text        not null,
    created_at timestamptz not null default now()
);

-- Terms are stored normalised, so the same word cannot be added twice.
create unique index ux_topic_filters_topic_kind_term on topic_filters (topic_id, kind, term);

create index ix_topic_filters_topic on topic_filters (topic_id);

-- Why an article never reached its topic; null for the ones that were delivered.
-- Filtered articles are recorded here so a rejected item is not re-evaluated —
-- and not paid for a second time — on every following cycle.
alter table seen_articles
    add column filtered_reason text;
