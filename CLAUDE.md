# Rules

- Java 21, Spring Boot 4.1 (not 3.x — the repo wins over the spec), Gradle.
  Build: `cd app && ./gradlew build`. Tests: `./gradlew test`.
- Full spec: `docs/SPEC.md`. Read it before changing behaviour.
  Deviations and assumptions: `DECISIONS.md`. Stage status: `PROGRESS.md`.
- Never commit secrets. Never hardcode chat ids or tokens. `.env` is git-ignored.
- **Telegram Bot API has NO method to list forum topics.** The `topics` table is
  the source of truth. Everything the bot knows about a topic comes from
  `forum_topic_*` updates, from `/bind`, or from the `createForumTopic` response.
- After `createForumTopic` Telegram still sends `forum_topic_created` for that
  topic. Registry writes are upserts on `(chat_id, thread_id)`, and the welcome
  message is claimed with a conditional UPDATE. Do not weaken either.
- `sendMessage` without `message_thread_id` lands in General. Command replies must
  carry the thread id of the incoming message.
- Rate limit is ~20 messages per minute per **group**, shared by its topics.
  Everything outbound goes through `TelegramSender`; do not call `bot.execute`
  directly for sends.
- `markSent` happens only after Telegram accepted the message.
- User-facing text lives in `messages.yml`, not in Java.
- Summaries: `SUMMARIZER=ollama` (default) uses the local model in the `ollama`
  compose service; `anthropic` and `extractive` are the alternatives. Only the
  headline and the feed description are ever sent to a model — never the full
  article. Every backend must fall back to `ExtractiveSummarizer` on any failure.
- Flyway migrations already applied are immutable — add a new `V<n>__*.sql`.
- Tests must pass offline: `./gradlew test` uses no network and no Docker.
  Integration tests are opt-in via `INTEGRATION_TESTS=true`.
- Every change must keep `docker compose up` working.
- Small commits, conventional commit messages.
- If a requirement is ambiguous: stop, ask, record the assumption in
  `DECISIONS.md`.

### Rules:
@.claude/rules/working.md
