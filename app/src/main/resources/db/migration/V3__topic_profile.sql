-- What a topic is actually about, in words, and where its search query came
-- from. A slug like "java" names two unrelated subjects; the domain is what
-- tells the search and the model which one the reader meant.
alter table topics
    add column domain text;

-- SLUG   - nothing derived yet, the query is the slug itself
-- MODEL  - derived once from the slug by the configured model
-- USER   - set by hand with /query and never overwritten automatically
alter table topics
    add column profile_source text not null default 'SLUG';
