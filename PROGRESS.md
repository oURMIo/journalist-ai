# Progress

Stages from `docs/SPEC.md` §12. Verified means the acceptance criterion was
actually executed here, not just implemented — see the note at the bottom.

## Stage 1 — Skeleton hardening ✅

- [x] PostgreSQL added to `docker-compose.yml` with a health check
- [x] Flyway migration `V1__init.sql` (`topics`, `seen_articles`, `summary_cache`)
- [x] `/actuator/health` plus a custom `newsPolling` indicator
- [x] `BotProperties` — every setting of §8 bound from the environment
- [x] `ALLOWED_CHAT_IDS` filter in `UpdateHandler`
- [x] Startup fails loudly when `BOT_TOKEN` or `ALLOWED_CHAT_IDS` is missing

## Stage 2 — Command router ✅

- [x] Echo handler became `UpdateHandler` + `CommandRouter`
- [x] `/start`, `/help` from `messages.yml`
- [x] Every reply carries the incoming `message_thread_id`
- [x] Unknown commands are ignored silently
- [x] `/status@botname` resolves to `/status`

## Stage 3 — Topic registry ✅

- [x] `topics` table, upsert on `(chat_id, thread_id)`
- [x] `forum_topic_created` / `edited` / `closed` / `reopened` handled
- [x] `forum_topic_edited` with a null name does not wipe the stored name
- [x] `TopicSlugParser` — emoji, case, `news_`, custom prefix, non-news names
- [x] `/bind`, `/unbind`, `/topics`

## Stage 3a — Topic creation by the bot ✅

- [x] `TopicProvisioningService` as the single creation path
- [x] `WelcomeMessageFormatter` + `messages.yml`, welcome sent whichever way a
      topic was created
- [x] `/newtopic`, `/newtopics`, `/renametopic`, `/closetopic`, `/presets`
- [x] `TopicBootstrapRunner` + `BOOTSTRAP_TOPICS`, idempotent
- [x] Welcome-once claim covers the duplicate `forum_topic_created` update
- [x] Batch creation pauses between calls and does not stop at the first failure
- [x] Missing `can_pin_messages` is a warning, not a failure

## Stage 4 — News retrieval ✅

- [x] `NewsProvider`, `RssNewsProvider` (Rome), `GoogleNewsRssProvider`
- [x] Presets in `topic-presets.yml`, fallback to Google News search
- [x] Google News redirect resolution and ` - Source` tail stripping
- [x] Parallel feed fetch on virtual threads, capped at 8
- [x] Timeouts, retries, a real User-Agent; one dead feed does not stop a cycle

## Stage 5 — De-duplication and formatting ✅

- [x] `seen_articles`, canonical URL plus normalised-title hashing
- [x] `ExtractiveSummarizer` (Jsoup, 2–3 sentences)
- [x] HTML message template of §7, escaping and truncation

## Stage 6 — Scheduler and delivery ✅

- [x] `NewsPollingJob` on `POLL_INTERVAL_MINUTES`
- [x] Single outbound queue, per-chat throttle, `429` with `retry_after`
- [x] `markSent` only after Telegram accepted the message
- [x] `/now`, `/status`

## Stage 7 — Polish ✅

- [x] README, `.env.example`, `Makefile`
- [x] Graceful shutdown that drains the queue
- [x] Daily `seen_articles` retention job
- [x] Summaries from a local Ollama container (default), with a hosted Anthropic
      alternative, a per-article cache, an hourly budget and an extractive fallback

## Stage 8 — News filters ✅

Beyond `docs/SPEC.md`, see `DECISIONS.md` §14.

- [x] `topic_filters` table (`V2`), `EXCLUDE` / `INCLUDE` terms per topic
- [x] `/filter`, `/exclude`, `/include`, `/filter remove`, `/filter clear`
- [x] Whole-word matching over headline and description: `go` != `Google`
- [x] Model check by meaning on top of the keywords, reusing the configured
      summariser backend through `LlmGateway`
- [x] Any model failure falls back to the keywords, never to silence
- [x] Model rejections recorded in `seen_articles.filtered_reason`, so nothing is
      judged twice; keyword rejections stay re-appliable
- [x] `last_polled_at` advances only past finished work, so an article the model
      could not judge and a send that failed are fetched again
- [x] Filter checks capped at half the hourly LLM budget

## Stage 9 — What a topic is about ✅

Beyond `docs/SPEC.md`, see `DECISIONS.md` §15. Answers "my `news-java` topic
gets volcano news".

- [x] `topics.domain` + `topics.profile_source` (`V3`), `TopicProfile`
- [x] `TopicProfiler` — one model call per topic turns the slug into the subject
      behind it plus a query that excludes its namesakes
- [x] Derived on the first poll, never on an incoming update, so no Telegram
      update waits for a model — and topics created before this get a subject too
- [x] Conditional write: the scheduler and a `/now` cannot store two profiles or
      announce twice; a backend that answers nothing is not asked again for 15
      minutes, while an answer in the wrong shape costs only that one topic
- [x] Query priority `USER` > preset > `MODEL` > slug; feeds only from a preset
- [x] The derived subject is announced once in the topic, with the query that
      will really be used; `/query`, `/query <text>`, `/query auto` overrule it
- [x] `/query <text>` drops the derived subject with it, so a misread topic
      cannot keep blocking by meaning what the new query finds
- [x] A hand-written query survives a rename that only changed the emoji; a
      rename to another subject starts over
- [x] The relevance check is given the subject, and now runs for a topic with a
      known subject and no keyword rules at all
- [x] No model, `TOPIC_PROFILE=false` or an unparsable answer leaves the topic
      searching for its own name, exactly as before

## Tests

| Run | Tests | Needs |
|---|---|---|
| `./gradlew test` | 184 | nothing — no network, no Docker |
| `INTEGRATION_TESTS=true ./gradlew test` | 199 | one-time download of an embedded PostgreSQL |

## Verified here vs. not

Verified by running it: the build, all 199 tests, the Flyway migrations and
Hibernate schema validation against a real PostgreSQL, both unique indexes
(including the partial one), the de-duplication round trip, and a full Spring
context boot with Telegram mocked.

**Not verified:** `docker compose up -d`, the Ollama container with its model
pull, and a live Telegram session — Docker was not running on the build machine.
`docker compose config` validates the compose file, and the Ollama client is
covered by tests against a stub server. See `DECISIONS.md` §13.
