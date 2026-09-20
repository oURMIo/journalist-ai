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
  still never fetched (§11, and a legal risk). Narrowed later by §19: when the
  feed description is too thin to summarise, the page's own preview text and
  opening paragraphs are read as well — still not the whole article.
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


## 15. An ambiguous topic name is read once, by the model

Reported after §14: a topic named `news-java` was served news about the island
and its volcanoes. Nothing in the bot was broken — `TopicQueryResolver` turned
the slug into the query `java`, Google News answered that word honestly, and
"java" names two unrelated subjects.

Filtering cannot fix this. A filter removes articles; it cannot make the search
return the ones nobody asked for. A topic where 90% of the results are volcanoes
would go near-silent instead of going wrong, which is not better. The fix has to
happen before anything is fetched, in the query itself.

So the slug is read once, by whichever model `SUMMARIZER` already configured,
through the same `LlmGateway` the filter uses. The answer is two things, and both
are stored on the topic (`V3__topic_profile.sql`):

| column | what it is | what uses it |
|---|---|---|
| `query` | a search expression that finds the subject and not its namesakes | both providers |
| `domain` | the subject in one sentence | the relevance check, as prompt context |

`profile_source` says where the query came from — `SLUG`, `MODEL` or `USER` —
because the three must not overwrite each other.

### Deriving happens on the first poll, not at registration

A model call takes seconds on a hosted backend and up to a minute on a 3B model
on CPU. An incoming `forum_topic_created` update must not wait for that, and the
welcome message must not be delayed by it. The polling job is already a
background job with nobody watching, so `TopicProfileService.queryFor` derives
there. The side effect is the useful one: every topic that existed before this
change also gets a subject, on its next cycle, with nobody doing anything.

The write is conditional — `applyDerivedProfile` only fires while the source is
still `SLUG` — so the scheduler and a `/now` running at the same moment cannot
store two profiles or announce twice. Same idiom as the welcome claim in §9. Two
threads can still *ask* twice, which costs one extra call and nothing else.

A backend that answered *nothing* is not asked again for fifteen minutes. A
wedged Ollama
answers with a timeout rather than an error, `OLLAMA_TIMEOUT_SECONDS` defaults to
120, and deriving happens at the head of `pollTopic`: without the cooldown, ten
un-profiled topics would burn twenty minutes before the first feed of the cycle
was fetched, and one `/now` would block every other command for two. The cooldown
is shared by all topics because the backend is one process, and so is its
outage. Only a blank answer arms it: `ask` returns `""` for a timeout, a non-2xx
and a spent budget, so a non-blank answer proves the backend is up. An answer in
the wrong *shape* is one slug the model handled badly — arming the cooldown on
that would let a single awkward slug, always polled first in a stable topic
order, keep every other topic from ever being profiled.

### The reader is told, and can overrule

The derived subject is posted once into the topic itself, together with the query
that will actually be searched — which for a topic with a preset is the preset's,
not the derived one. A guess that shapes the feed silently is worse than a guess
the reader can see: `/query` shows the subject, the query and where it came from,
`/query <text>` replaces it, and `/query auto` hands it back to the model. A
`USER` query is never overwritten — not by a later derivation, and not by a
rename that only changed the emoji. `register` now rewrites the profile only when
the slug actually changed, which is the one case where the old subject really is
wrong.

**`/query <text>` also clears the derived subject, and that is the important
half.** A reader types it precisely when the model read the topic wrong, and a
wrong subject is not a cosmetic leftover: the relevance check would go on
blocking, by meaning, exactly the articles the new query was written to find, and
a model rejection is *remembered* (§14), so the topic would deliver nothing at
all and no command would bring it back. With no subject stored, the topic is
judged by its keyword rules alone — the behaviour that shipped before this
change. Only `/query auto` asks the model again.

Priority when a topic is polled: `USER` > preset > `MODEL` > the bare slug. A
preset still wins over a derived query because its feeds are curated by hand and
its query was written to go with them; a reader who disagrees types `/query`.

### The relevance check now has the subject to check against

§14 gave the model the topic slug, which for `java` says exactly as little to the
model as it did to Google. `TopicFilters` therefore carries the domain, and
`ArticleFilter` no longer short-circuits on "no keyword rules": a topic with a
known subject is checked even when the reader typed nothing. That is what catches
the wrong meaning arriving through a curated feed, which answers no query at all.

The cost is one model call per article per topic, where before an unfiltered
topic cost none. With the default local Ollama that is free and slow; on
`SUMMARIZER=anthropic` it stays inside the same half-budget as §14, and
`FILTER_AI_CHECK=false` switches it off. An `UNKNOWN` verdict here publishes the
article: a topic whose model is down must go on delivering news.

### Everything about it is optional

No model, `TOPIC_PROFILE=false`, `SUMMARIZER=extractive`, an unreachable Ollama,
an answer in the wrong shape — the topic keeps searching for its own name, which
is precisely the behaviour that shipped before this change, and a later cycle
tries again. A backend that is down costs one call per fifteen minutes rather
than one per topic per cycle; a model that keeps mangling one particular slug
costs one call per cycle for that topic and holds up nothing else.

The model's query is not second-guessed. It is cleaned — one line, no brackets or
backticks, cut at a word boundary at 200 characters — and the excluded meanings
it names are appended as `-term`, which both providers pass through. Rejecting a
query for "not looking related enough to the slug" was considered and dropped: it
would have thrown away good queries like `diplomacy OR geopolitics` for
`foreign-policy`, and `/query` is the escape hatch that makes the guess safe.

## 16. A closed topic stops receiving news

### The cycle that is already running

`docs/SPEC.md` §2 says a `forum_topic_closed` clears `active`, and it does — but
`active` was only read once, when `NewsPollingJob.pollAll` took its list of
topics. A cycle lives much longer than that snapshot: the send throttle paces one
message per three seconds for the whole group, the relevance check is a model
call per article, and a batch is up to `MAX_ITEMS_PER_TOPIC` items. A topic
closed while its own batch was being published therefore kept receiving the rest
of it.

Telegram does not stop this on its own: a bot with `can_manage_topics` may post
into a closed topic, so `sendMessage` returns `ok: true` and the messages land.

`TopicRegistryService.isActive(topicId)` re-reads the flag, and `pollTopic` asks
twice: once before it fetches anything, so a topic closed between the snapshot
and its turn costs no feed request and no model call, and once per article,
before the filters and the summariser run. The article that did not go out sets
the same `unfinished` watermark a failed send sets, so `last_polled_at` does not
move past it and a reopened topic resumes where it stopped instead of skipping
the batch.

What is deliberately not covered: the one message already handed to
`TelegramSender` when the close arrives. The polling job publishes through
`sendBlocking`, so at most a single news message can be in flight, and dropping
it inside the sender would mean teaching the queue which messages are news — the
`/closetopic` confirmation itself is sent into the topic that is being closed.

### The open/closed state is its own column

`docs/SPEC.md` §2 and §4 say a `forum_topic_closed` clears `active`, and that is
what shipped — but `active` answers a different question. It means "is this topic
ours to poll", and the thing that decides it is the name: `register()` sets it on
every `forum_topic_created`, `forum_topic_edited` and `/bind`, unconditionally.
So a topic that was closed and then renamed — a new emoji is enough — came back
as active and started receiving news again, into a topic nobody had reopened.

`V4__topic_closed.sql` therefore adds `topics.closed`, written only by
`forum_topic_closed` / `forum_topic_reopened` and by `/closetopic`. `register()`
keeps owning `active` alone and never touches it, so a rename can no longer
reopen anything. The polling job asks for both: `pollableTopics()` and
`isPollable(id)` mean active **and** open, which is also what `/status` counts
and what `/now` requires.

Consequences worth knowing:

- A closed topic keeps `active = true`, so it keeps its subject under the partial
  unique index `(chat_id, slug) WHERE active`. `/newtopic it` while `news-it` is
  closed answers "Topic 'it' already exists." instead of creating a second
  topic with the same name, and `BOOTSTRAP_TOPICS` no longer recreates a
  closed topic on every restart — which it did, because a closed topic used
  to be inactive and therefore invisible to the duplicate check. Reopening
  the topic is what brings the news back.
- `/closetopic` records the flag even when `closeForumTopic` itself came back
  not-ok. The reader asked for the news to stop; stopping it and leaving the
  topic open on Telegram's side is the harmless direction of that failure.
- `/unbind` still clears `active`: "not mine any more" is exactly what that flag
  is for, and it is not a statement about Telegram's own state.
- `/topics` marks a closed topic 🔒, so the registry listing does not show it
  green while nothing is being sent there.
- No backfill. A topic closed before this migration is `active = false,
  closed = false`: it stays unpolled, which is the intended outcome, but a rename
  of that row would still revive it. Closing it once more writes the new flag and
  it is fixed for good.

## 17. The build context is `./app`, so `.dockerignore` lives there

`docker-compose.yml` builds the bot with `context: ./app`, and Docker reads
`.dockerignore` from the root of the build context. The repository-root
`.dockerignore` therefore never applied to that build: `COPY src ./src` pulled
the git-ignored `app/src/main/resources/config.properties` (see [12](#12-configproperties-kept-as-a-local-dev-fallback))
into the context, into `BOOT-INF/classes/config.properties` in the bootJar and
into the image.

`app/.dockerignore` is the file that governs this build; the root one is kept
with depth-independent patterns (`**/config.properties`) only for a hypothetical
root-context build. Do not consolidate the two back into one file at the root.

Verified with a BuildKit context probe against `./app`: `config.properties` is
absent from the context, every input the build stage needs (`gradlew`,
`settings.gradle`, `build.gradle`, `gradle/wrapper/*`, the rest of `src`) is
present.

## 18. All user-facing bot text is English

`docs/SPEC.md` spells out the welcome message, the `/help` listing and several
command replies in Russian. The bot publishes English-language news only, and
the reader of a news topic is reading English anyway, so the interface text now
matches the content: `messages.yml` and the remaining literals in
`StatusCommand`, `TopicsCommand` and `DefaultTopicProvisioningService` are
English. This supersedes the Russian samples in `docs/SPEC.md` — they stay as a
record of the original wording, not as the text to restore.

Nothing else changed: every `messages.yml` key, its placeholders and its HTML
markup are the same, so the rendering and the `MessageTemplates` binding are
untouched.

`QueryCommand` still accepts `авто` and `сброс` alongside `auto` and `reset`.
Those are inputs, not output — keeping them costs nothing and does not break a
reader who learned them before this change.

## 19. A thin feed description is filled in from the article page, against spec §11

Spec §11 says the summariser's input is *"заголовок + описание из фида (**не**
качаем полный текст статьи — это отдельная задача и юридический риск)"*. In
practice Google News — the fallback provider behind every topic without a preset
— gives a description that is a link stub, so the prompt was effectively a bare
headline and `llama3.2:3b` answered *"I don't have any information about the news
item"*, which then went into the topic and into `summary_cache`. **The customer
asked for the model to be given more context from the article in that case**, so
the spec is overridden here on their instruction.

What changed:

- `ArticleContextFetcher` (package `com.dach.sagiri.news`, next to `HttpFetcher`)
  reads the article page **once**, without retries, when the cleaned feed
  description is shorter than `ARTICLE_CONTEXT_MIN_DESCRIPTION_CHARS` (200).
- It takes `og:description` / `meta[name=description]` — the text every site
  already hands to link previews — plus the opening paragraphs of
  `article`/`main`/`[itemprop=articleBody]`, dropping lines under 80 characters
  (captions, bylines, cookie notices), and caps the result at
  `ARTICLE_CONTEXT_MAX_CHARS` (1500). This is the compromise the customer chose
  over both "meta description only" and "the whole article text".
- Nothing is fetched for a Google News link the provider could not resolve: the
  stub serves Google's own interstitial, and its description is not the article's.
  A body that declares more than 2 MB is dropped instead of buffered — a feed
  link can point at a PDF or a video, and the polling thread is waiting.
- The prompt gained `Source:` and `Article:` lines, and its last instruction
  changed from *"If the input is insufficient, return the description as is"* —
  which is what invited the refusals — to *"Never state that information is
  missing … summarize the headline itself"*.
- `LlmSummarizer.isNonAnswer` treats a refusal or an answer under 25 characters
  exactly like a failed call: fall back to `ExtractiveSummarizer`, **do not
  cache**. A non-answer that reached the cache used to be permanent.
- The fetched context also feeds the fallback
  (`ExtractiveSummarizer.summarize(text, title)`), so a topic gets real sentences
  even when the model is unreachable.

What deliberately did not change:

- Nothing is fetched for `SUMMARIZER=extractive`: the fetcher hangs off
  `LlmSummarizer`, not off the extractive path, so the model-free default stays
  free of extra requests.
- Nothing is fetched for an article that the filters dropped — `summarize` is
  called at publish time only.
- The article text is never stored; only the generated summary is cached, as
  before.
- `ARTICLE_CONTEXT=false` restores the §11 behaviour exactly.

The per-cycle cost is at most one extra request per published article with a thin
description (`MAX_ITEMS_PER_TOPIC=5` by default), taken with the existing
`HTTP_TIMEOUT_SECONDS` and no retry, because a summary is worth one request and
never a stalled polling cycle.

## 20. Google News links are decoded, not followed

`docs/SPEC.md` §10 treats a Google News item like any other: take the link, follow
the redirect, publish it. That stopped being true. A
`news.google.com/rss/articles/CBMi...` stub now answers **200 with an application
shell that contains no link at all**, so `resolveFinalUrl` (a `HEAD` that reports
where it landed) hands back the stub itself. Two things followed from that:

- the reader's "Read full article" opened a Google interstitial rather than the
  article;
- §19's fetcher had nothing to read, so the recap fell back to the feed
  description — and a Google News description is the headline and the source
  wrapped in an `<a>`, nothing more. The message then repeated its own headline
  as the summary, which is what the customer reported.

`GoogleNewsLinkDecoder` therefore reads the stub page for the `data-n-a-ts` and
`data-n-a-sg` values it carries and posts them to
`/_/DotsSplashUi/data/batchexecute`, which is what Google News itself calls to
open an article. The answer holds the publisher URL. Verified against the live
endpoint while implementing this: a `fool.com` stub decoded to the article.

This is an **undocumented endpoint and it will break some day**, so nothing
depends on it: `decode` returns an empty `Optional` on any failure, the redirect
is then followed exactly as before, and an item that cannot be resolved is still
published with its stub link. The decoder also declines any URL without an
`/articles/` segment, which keeps the older stub format on the redirect path.

Two related changes fell out of the same report:

- `GoogleNewsRssProvider` now clears `rawDescription` for its items. It is a link
  stub, not content: keeping it only gave the extractive summariser the headline
  to echo. With it gone, §19's fetcher reads the decoded article instead.
- `MessageFormatter.recap` drops a summary that repeats the headline — unless it
  goes on to add something, because a recap built from an article's own
  `og:description` often opens with the headline and then carries the news. Belt
  and braces: whatever a feed or a model does, the line under a headline is
  either new information or nothing.

The cost is honest about itself: two extra requests (the stub page, then the
RPC) per Google News link the process has not decoded yet. `GoogleNewsRssProvider`
normalises everything it fetched, which is `MAX_ITEMS_PER_TOPIC` times the
aggregator's overfetch factor — twenty links for the default five messages — so a
topic polled with nothing cached pays for twenty decodes to publish five. Both
requests go through `HttpFetcher` with the configured timeout and no retries, the
result is cached in the existing `resolvedLinks` map, and `GOOGLE_NEWS_DECODE=false`
turns the whole thing off for a host that Google starts rate-limiting. Resolving
links only for the items that survive de-duplication and the filters would cut
that fourfold; it moves link resolution out of the provider and into the polling
job, and is deliberately left as a separate change.

**A failed decode is not cached.** Only a URL that actually left `news.google.com`
is remembered: one 429 must not pin every link of that cycle to a stub for the
life of the process.

