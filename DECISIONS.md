# Decisions log

Assumptions and deviations from `docs/SPEC.md`. Per spec §0, where the existing
repository disagreed with the spec, the repository won and the deviation was
recorded here.

## 1. What was reused from the existing skeleton, and what was replaced

The skeleton was read before any code was written. Reused as-is:

| Kept | Why |
|---|---|
| Gradle wrapper, `build.gradle` layout, Java 21 toolchain, Jacoco | Working build, no reason to touch it |
| `com.github.pengrad:java-telegram-bot-api` and long polling | The Telegram channel was already proven to work |
| `bot/BotInitializer` | Still the component that registers the updates listener |
| `config/BotConfig` | Still the place where the `TelegramBot` bean is built |
| The "map commands by their first word" dispatch of `CommandService` | Correct approach; it became `CommandRouter` |
| `logback.xml`, `.gitignore` | Fine as they were |

Replaced, and why:

| Replaced | Became | Why |
|---|---|---|
| `service/BotMessageService` (echo handler) | `telegram/UpdateHandler` | Spec §12 stage 2 asks for exactly this conversion; the update loop and its error handling were carried over rather than rewritten |
| `service/CommandService` | `telegram/CommandRouter` | Same lookup, plus admin checks, `@botname` stripping and topic-aware replies |
| `bot/command/BotCommand` | `telegram/command/BotCommand` | The `(bot, message)` pair could not express "reply into this topic"; it now takes a `CommandContext` |
| `property/BotConfigProperty` | `config/BotProperties` | One `@Value` field cannot carry the ~20 settings of §8 |
| `bot/command/HelpCommand`, `PingPongCommand`, `bot/callback/**`, `service/CallbackService` | deleted | Demo echo/ping code. v1 has no inline buttons (§1 non-goals), so the callback infrastructure had no user and would have been dead code |
| `property/dto/{Project,UsefulUrl}` | deleted | Unused leftovers from another project |
| `SagiriApplicationTests` | `ApplicationContextTest` | The old test was `@Disabled` and called `main()`, which would start real long polling |

## 2. Package root stays `com.dach.sagiri`

The spec sketches `com.example.newsbot`. Per §0 the actual repository wins, so the
root package is unchanged and the spec's module names became sub-packages:
`telegram`, `topic`, `news`, `summary`, `dedup`, `schedule`, `config`, plus
`health` for the actuator indicator.

## 3. Spring Boot 4.1.0, not 3.x

The skeleton was already on Spring Boot 4.1.0 with Gradle 9.5. Per §0 the
repository wins. Consequences worth knowing:

- health indicators live in `org.springframework.boot.health.contributor`, not
  `org.springframework.boot.actuate.health`;
- test slices moved: `@DataJpaTest` is in `spring-boot-data-jpa-test`,
  `@AutoConfigureTestDatabase` in `spring-boot-jdbc-test`;
- **Flyway auto-configuration is no longer pulled in by `flyway-core` alone.** It
  lives in the `org.springframework.boot:spring-boot-flyway` module, which is now
  an explicit dependency. Without it migrations silently never run and Hibernate
  fails validation on an empty database. This was caught by running the schema
  against a real PostgreSQL before shipping.

## 4. Telegram library is pengrad, not telegrambots

The spec names no library. The skeleton uses `pengrad`, which covers everything
§2 requires: `createForumTopic`, `editForumTopic`, `closeForumTopic`,
`pinChatMessage`, `editMessageText`, and the `forum_topic_*` service messages.

## 5. Testcontainers replaced by an embedded PostgreSQL

Spec §13 asks for Testcontainers and, in the same section, for `./gradlew test` to
run offline. Those pull in opposite directions, and Testcontainers additionally
needs a Docker daemon that CI may not have.

Integration tests therefore run against `io.zonky.test:embedded-postgres`, a real
PostgreSQL server started as a child process — no Docker required. They are gated
behind `INTEGRATION_TESTS=true` because the first run downloads the server binary.

- `./gradlew test` — 90 tests, no network, no Docker.
- `INTEGRATION_TESTS=true ./gradlew test` — 105 tests, including the Flyway schema,
  both unique indexes, the de-duplication round trip and a full application
  context boot with Telegram mocked out.

## 6. `ADMIN_USER_IDS` empty means "any member of an allowed chat"

§8 marks the variable optional but does not say what an empty value means.
Assumption: an empty list allows every member of an allowed chat, since
`ALLOWED_CHAT_IDS` already restricts the bot to groups the operator controls.
Documented in the README.

## 7. Two topics for the same subject

§4 requires a partial unique index on `(chat_id, slug) WHERE active`, but the spec
does not say what to do when a user creates a second `news-it` by hand. Chosen
behaviour: the newcomer is registered as **inactive**, a warning is logged and no
welcome is sent. `/newtopic` never reaches this path — it answers
`ALREADY_EXISTS` first.

## 8. `icon_color` index uses `Math.floorMod`

§9a specifies `abs(slug.hashCode()) % 6`. `Math.abs(Integer.MIN_VALUE)` is
negative, which would throw on an array index. `Math.floorMod` is the equivalent
that cannot break.

## 9. Welcome-once uses a claim marker, not a plain read

§9a requires "check and insert in one transaction", but the welcome has to be sent
over the network and a transaction must not span a Telegram call. Instead
ownership is taken with a conditional `UPDATE ... WHERE welcome_message_id IS
NULL` that writes a `-1` marker; exactly one caller sees one updated row. The real
`message_id` replaces the marker on success, and the claim is released on failure
so a later attempt can retry.

## 10. `markSent` after Telegram accepts, via a future

§15 forbids losing `markSent`. The outbound queue is the single send path, and
`enqueue` returns a `CompletableFuture`, so the polling job records an article
only once Telegram has actually accepted it. A failed send is simply retried on
the next cycle.

## 11. Summaries come from a local Ollama container, against spec §11

Spec §11 says explicitly: *"В Docker дополнительный контейнер не нужен: это
HTTP-клиент внутри того же приложения"*, with an API key passed through `.env`.
That was implemented first. **The customer then asked for Ollama to run alongside
the application and produce the recap, and said the point mattered to them**, so
the spec is overridden here on their instruction.

What changed:

- `docker compose up -d` now starts an `ollama` container plus a one-shot
  `ollama-init` that pulls the model into a named volume. Restarts reuse it.
- `SUMMARIZER` gained `ollama`, which is the **default**. `anthropic` (alias
  `llm`, the name §8 uses) keeps the hosted path; `extractive` keeps the
  model-free one.
- The summary is now **1–3 sentences** rather than the 2–3 of §7/§11 — the
  customer's wording.
- Default model `llama3.2:3b`, sized for the 3 GB container limit the customer
  specified.

What deliberately did not change:

- Only the headline and the feed description reach the model; the full article is
  still never fetched (§11, and a legal risk).
- Every backend still falls back to `ExtractiveSummarizer` on any failure, so the
  bot never goes silent. This matters more than before: on a first run the model
  pull takes minutes, and until it finishes the bot posts news using the feed
  description.
- The per-article cache still applies, so nothing is summarised twice.

The hourly budget is unlimited by default for Ollama (`max-calls-per-hour: 0`) —
a local model costs nothing per call. It stays at 100 for the hosted backend.

The Anthropic path uses the official Anthropic Java SDK. The Ollama path is a
plain `java.net.http` client against `POST /api/generate` with streaming off,
since no Anthropic SDK applies there.

## 12. `config.properties` kept as a local-dev fallback

The skeleton reads `bot.key` from a git-ignored `app/src/main/resources/config.properties`.
The import is now `optional:` so a clean clone starts without it, and `BOT_TOKEN`
takes precedence. **The file currently in this working copy contains a real-looking
bot token — it is git-ignored, but it should be revoked via @BotFather if it was
ever a live token.**

## 13. Not verified in this environment

Docker was not running on the machine where this was built, so
`docker compose up -d` has not been executed end to end, and neither has a live
Telegram session. What *was* verified: the full build, 94 tests, the Flyway
migration and Hibernate schema validation against a real PostgreSQL, and a full
Spring context boot.
