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

## 14. Topic filters: keywords first, the model second

Not in `docs/SPEC.md` — requested afterwards: "interested in IT, but not in the
Go programming language". Two layers, because neither alone is enough.

**Keywords** live in a new `topic_filters` table (`V2__topic_filters.sql`), one
row per term, `EXCLUDE` or `INCLUDE`, edited from Telegram with `/exclude`,
`/include`, `/filter remove`, `/filter clear`. Matching is whole-word over the
headline plus the feed description: both sides are reduced to lowercase words
separated by single spaces, so `go` hits "Go 1.24 is out" and misses "Google" —
a plain `contains` gets exactly this case wrong. Commas separate terms, spaces do
not, so `machine learning` stays one term.

**The model** catches what the literal word cannot: *Golang*, *the Go team*,
*gopher*. It is asked for one word, `ALLOW` or `BLOCK`, and only when the
keywords let the article through — a literal hit blocks without paying for a
call. There is no second backend: `AiRelevanceChecker` reuses whichever
summariser `SUMMARIZER` already selected, through the new `LlmGateway` interface
that `LlmSummarizer` implements, so the hourly budget, the timeouts and the
"only the headline and the description ever leave the process" rule all continue
to hold.

Anything short of a clear verdict — model down, model slow, budget spent,
`SUMMARIZER=extractive`, an unparsable answer — is `UNKNOWN`, and an article that
broke no keyword rule is then published. The filter narrows the feed; it must
never silence it. The one place `UNKNOWN` blocks is a topic with an `INCLUDE`
list and no matching keyword, which is the rule the user typed by hand.

An `INCLUDE` list is a hint rather than a gate: a model `ALLOW` overrules a
missing required keyword, so `/include kubernetes` still delivers an article
titled "K8s operators in practice".

### Only a model verdict is remembered

`V2` adds a nullable `filtered_reason` to `seen_articles`, and `markFiltered`
writes a row there — but only for a rejection the model actually answered.
Without it a blocked article would be re-judged on every cycle, 48 model calls a
day for one article nobody wants. `markSent` is untouched and still happens only
after Telegram accepted the message; the new column is what tells the two apart.

A keyword rejection is *not* stored: it costs nothing to apply again, and a
stored row would outlive the term that caused it.

### The look-back mark is what actually decides "next cycle"

Storing or not storing a rejection only matters if the article can come back at
all, and by default it cannot: `last_polled_at` is the look-back start of the
next cycle (§6 of the spec), and both providers keep only
`publishedAt.isAfter(since)`. A cycle that ends with `markPolled(now)` therefore
buries everything it walked past — an article it could not judge, and one whose
send failed, the comment about "retried on the next cycle" notwithstanding.

So the mark now moves to `now` only when the cycle finished its work. Anything
left unresolved — a send that failed, or a model that was there to answer and
could not — holds it just before that article, and the next cycle fetches it
again. Hence the three ways of rejecting an article in `FilterDecision`:

| outcome | when | next cycle |
|---|---|---|
| `BLOCK` | keyword hit, or a required keyword missing with no model configured | applies the same rule again, for free |
| `BLOCK_AND_REMEMBER` | the model said BLOCK | never sees it again: the row in `seen_articles` |
| `POSTPONE` | a required keyword missing and the model could not answer | fetches and asks again |

`POSTPONE` cannot stall a topic for long: the look-back never reaches further
back than `NEWS_LOOKBACK_HOURS`, so an article the model keeps failing on drops
out of the window after a day.

The remaining cost: an article the model blocked stays blocked after the term is
removed. With a 24-hour look-back that is a non-issue, and a second cache table
with its own retention buys nothing.

### The filter check may spend half the hourly budget

`ask` and `summarize` share one sliding window, and the filter runs first, so
without a limit a filtered topic could spend the whole hosted-LLM budget on
verdicts and leave every recap of the cycle extractive. `ask` therefore stops at
`maxCallsPerHour / 2`. The configured ceiling still holds — filtering never
raises the bill above `LLM_MAX_CALLS_PER_HOUR` — and the local backend, where the
budget is unlimited by default, is unaffected.

### What a term may be

Terms are stored normalised, and normalisation drops everything that is not a
letter or a digit. `C++` and `C#` both become `c`, which would block every
headline containing a stray "C", so a term shorter than two characters is
rejected outright — those two languages cannot be filtered by keyword, and the
model check is what catches them. A term longer than 80 characters is cut at a
word boundary, because matching compares whole word sequences and a term cut
mid-word could never match anything while still occupying one of the 30 slots.

### A chatty model still gets read correctly

The prompt asks for one word, and a 3B model answers "ALLOW, there is nothing to
block here". Scanning for `BLOCK` anywhere would drop that article — and, being a
model verdict, drop it permanently. The verdict is therefore read from the start
of the answer only (behind a short `Answer:` label, if any). "I would not block
this one" carries no verdict and is `UNKNOWN`: the two mistakes are not
symmetric, since a wrong `BLOCK` is stored and a missing verdict costs one more
attempt.

