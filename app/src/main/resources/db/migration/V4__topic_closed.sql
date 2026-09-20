-- Telegram's own open/closed state of a topic, which is a different question
-- from `active`: `active` answers "is this topic ours to poll", and a rename
-- decides it. Closing and reopening decide this one. Kept apart so that a
-- rename of a closed topic cannot put news back into it.
alter table topics
    add column closed boolean not null default false;

-- What the polling job actually asks for.
drop index ix_topics_active;
create index ix_topics_pollable on topics (active, closed) where active and not closed;
