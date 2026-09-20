# Journalist — Telegram news bot

Publishes fresh English-language news into the forum topics of a Telegram
supergroup. Create a topic called `news-biotech` and the bot starts posting
biotech news there — no restart, no code change.

Each item is one message: headline, a 1–3 sentence recap, the publication time in
UTC, and a link to the original. The recap is written by a local Ollama model
that runs alongside the bot — no API key, no per-article cost, and nothing leaves
the compose network.

---

## Quick start

```bash
git clone <repo> && cd journalist
cp .env.example .env
# fill in BOT_TOKEN, BOT_USERNAME, ALLOWED_CHAT_IDS, DB_PASSWORD
docker compose up -d
docker compose logs -f bot
```

`make up` / `make logs` / `make down` do the same.

Three containers come up: the bot, PostgreSQL, and **Ollama**. A fourth,
`ollama-init`, downloads the summarisation model once and exits — on a first run
that takes a few minutes and roughly 2 GB of disk. Until it finishes, the bot
posts news with the plain feed description instead of a model-written recap, then
switches over on its own. Watch it with:

```bash
docker compose logs -f ollama-init
```

Health check: <http://localhost:8080/actuator/health>. It reports `DOWN` when the
last successful polling cycle is older than twice `POLL_INTERVAL_MINUTES` — a bot
that quietly stopped fetching news is not healthy.

---

## Telegram setup

This is the part that silently breaks things if skipped.

### 1. BotFather

1. `/newbot` — note the token and the username.
2. **`/setprivacy` → Disable.** With privacy mode on, the bot never sees messages
   or commands in a group and nothing works.

### 2. The group

1. Create a **supergroup** and turn **Topics** on.
2. Add the bot and make it an **administrator** with:
   - **Manage Topics** (`can_manage_topics`) — required to create and rename topics;
   - **Pin Messages** (`can_pin_messages`) — optional, only to pin the welcome
     message. Without it the topic still works and the bot logs a warning.
3. Get the chat id: forward any message from the group to `@userinfobot`, or read
   it from the bot's own log. It looks like `-1001234567890`.
4. Put it into `ALLOWED_CHAT_IDS`. Updates from any other chat are ignored — the
   bot can be added to a group you do not control.

### 3. Add the bot BEFORE creating the news topics

Telegram's Bot API has **no method to list the topics of a chat**. The bot only
learns about a topic from the `forum_topic_created` update, which is delivered
only to bots that were already in the group.

Topics that existed before the bot joined are invisible to it. For those, send
this **inside the topic in question**:

```
/bind news-it
```

---

## Two ways to get a news topic

**The bot creates it** (recommended — the API response carries the topic id, so
the registry is filled immediately):

```
/newtopic biotech          → creates "news-biotech" with a pinned welcome
/newtopics it science ai   → three topics in one go
```

`BOOTSTRAP_TOPICS=it,science,ai` does the same on start-up, and is idempotent:
restarting the container creates no duplicates and sends no second welcome.

**You create it by hand:** make a topic named `news-<subject>`. The bot picks it
up from the creation update and greets it exactly the same way.

Either way the subject comes from the topic name: `news-ai-safety` → `ai safety`.

---

## Commands

Every command is answered in the topic it was sent from.

| Command | Who | Where | Effect |
|---|---|---|---|
| `/start`, `/help` | everyone | anywhere | short instructions |
| `/newtopic <subject>` | admin | anywhere | create `news-<subject>` and greet it |
| `/newtopics <a> <b> …` | admin | anywhere | create several at once |
| `/presets` | admin | anywhere | curated subjects, marking those already live |
| `/bind [name]` | admin | inside a topic | attach a pre-existing topic |
| `/unbind` | admin | inside a topic | stop polling this topic |
| `/renametopic <subject>` | admin | inside a topic | rename and update the welcome |
| `/closetopic` | admin | inside a topic | close it; news stops until it is reopened |
| `/status` | admin | anywhere | active topics, last poll, counters |
| `/now` | admin | inside a topic | poll this topic immediately |
| `/topics` | admin | anywhere | dump the registry (🔒 closed, 🟢 polled, ⚪ not ours) |
| `/filter` | admin | inside a topic | show the filters of this topic |
| `/exclude <words>` | admin | inside a topic | never send news about these |
| `/include <words>` | admin | inside a topic | send only news about these |
| `/filter remove <word>` | admin | inside a topic | drop one filter |
| `/filter clear` | admin | inside a topic | drop them all |
| `/query` | admin | inside a topic | what this topic is about and what it searches for |
| `/query <text>` | admin | inside a topic | search for exactly this instead |
| `/query auto` | admin | inside a topic | hand the subject back to the model |

Unknown commands are ignored without a reply.

---

## What a topic is about

A topic name is one or two words, and a search engine answers those words
literally: `news-java` used to be served volcanoes, because Java is also an
island. So the first time a topic is polled, the configured model is asked once
what a reader of a feed with that name means, and the answer is stored with the
topic:

```
java  ->  the Java programming language, the JVM and its ecosystem
          "java programming" OR jvm OR jdk OR "spring boot" -"java island" -volcano
```

The bot posts that once into the topic, so a wrong guess is visible rather than
silently shaping the feed. Both halves are then used: the query is what the
sources are asked for, and the subject is what the relevance check judges each
headline against — which is what catches the wrong meaning in feeds that answer
no query at all.

Nothing about this is required. With `TOPIC_PROFILE=false`, with
`SUMMARIZER=extractive`, or when the model is simply unreachable, a topic keeps
searching for its own name exactly as before, and the next cycle tries again.

```
/query                                       # subject, query, where it came from
/query "java programming" OR jvm OR jdk      # search for this instead
/query auto                                  # derive it again
```

A query written by hand wins over everything and survives a rename that only
changed the emoji; renaming a topic to another subject starts it over. Google
News syntax works: `OR`, quotes for a phrase, `-word` to exclude. A preset
(`topic-presets.yml`) still outranks a derived query and keeps its curated feeds.

`/query <text>` also drops the derived subject, on purpose: you type it when the
model read the topic wrong, and a wrong subject would go on rejecting — by
meaning, permanently — the very articles the new query was written to find. The
topic then runs on its keyword filters alone until `/query auto`.

---

## Filters

A topic can be narrowed without leaving Telegram. Interested in IT but tired of
one programming language:

```
/exclude go, golang
/include kubernetes, databases     # optional: only these subjects
/filter                            # what is set right now
```

Terms are separated by commas, so a phrase stays one term
(`/exclude machine learning`). They are matched as whole words against the
headline and the feed description: `go` hits "Go 1.24 is out" and does not hit
"Google".

Whole words are not enough on their own — the same subject arrives as *Golang*,
*the Go team*, *gopher* — so an article that survived the keywords is also shown
to the summariser's model (`SUMMARIZER=ollama` by default), together with the
topic and its filters, and asked for one word: `ALLOW` or `BLOCK`. Only the
headline and the feed description are sent, exactly as for summaries.

A topic whose subject is known (see above) is checked this way even with no
keyword rule at all — that is what stops the wrong meaning of the name when it
arrives through a curated feed rather than through the search query.

The model narrows the feed, it never silences it: when it is down, slow or out
of budget the keywords alone decide, and an article that broke no rule is
published. Set `FILTER_AI_CHECK=false` to switch the model check off and match by
literal word only.

Articles the model rejected are recorded in `seen_articles` with the reason, so
the same article is not judged — or paid for — twice. A keyword rejection is not
recorded: it is free to apply again. And when the model was supposed to answer
and could not, the article is not dropped at all — the poll mark stays where it
is, so the next cycle fetches it and asks again.

Two terms are refused: anything shorter than two characters after normalisation
(`C++` and `C#` both reduce to `c`, which would block every stray "C" — the model
check is what catches those), and a term that survives only as half a word.
On `SUMMARIZER=anthropic` the filter may use at most half of
`LLM_MAX_CALLS_PER_HOUR`, so turning a filter on cannot make every recap
extractive.

---

## Sources

- **Presets** (`app/src/main/resources/topic-presets.yml`) — curated feeds for
  known subjects such as `it`, `science`, `ai`, `space`, `security`.
- **Google News search RSS** — the fallback for everything else. This is what
  makes an arbitrary new subject work with no configuration.

A preset improves quality; it is never a precondition. Add your own by editing
`topic-presets.yml` and rebuilding.

A Google News link is a redirect stub that no longer redirects, so the bot
decodes it to the publisher's own URL before publishing — both so that "Read full
article" opens the article and so that there is an article to summarise at all.
If Google ever stops answering, the item is still published — with its stub link
and, since a Google News description carries nothing but the headline, usually
with no recap line under it (`DECISIONS.md` §20). Decoding costs two requests per
link it has not seen yet, so `GOOGLE_NEWS_DECODE=false` turns it off if your host
ever gets rate-limited.

---

## Summaries

The recap under each headline comes from one of three backends, chosen with
`SUMMARIZER`:

| Value | What it does | Cost |
|---|---|---|
| `ollama` (default) | a local model in the `ollama` container writes 1–3 sentences | free, runs on your hardware |
| `anthropic` | hosted Claude, needs `LLM_API_KEY` | paid per article |
| `extractive` | no model: the first sentences of the feed description | free |

Summaries are cached per article, so the same story is never summarised twice.
Any failure, timeout or missing model falls back to the extractive path, so a
slow or absent model never leaves a topic empty — and an answer that is not a
summary at all ("I don't have any information about the news item") is treated
as a failure: it is neither published nor cached.

### When the feed says nothing

Google News and several RSS feeds hand over a headline and a link stub instead
of a description, and a model asked about nothing answers that it knows nothing.
A summary that only repeats the headline is never published: the line under a
headline is either new information or nothing.

With `ARTICLE_CONTEXT=true` (the default) a feed description shorter than
`ARTICLE_CONTEXT_MIN_DESCRIPTION_CHARS` makes the bot read the article page once
and take its preview text (`og:description`) plus the opening paragraphs, capped
at `ARTICLE_CONTEXT_MAX_CHARS`, as the input for the recap. The article is never
mirrored or stored, only summarised; set `ARTICLE_CONTEXT=false` to send the
headline and the feed description alone.

The default model is `llama3.2:3b`, chosen to fit the 3 GB limit set on the
container in `docker-compose.yml`. To use another one, set `OLLAMA_MODEL` in
`.env` and raise that limit if the model is bigger:

```bash
OLLAMA_MODEL=qwen2.5:3b
docker compose up -d ollama-init   # pulls the new model
docker compose restart bot
```

A 3B model on CPU needs roughly 10–60 seconds per article; with the default
`MAX_ITEMS_PER_TOPIC=5` that is comfortably inside a 30-minute polling cycle. If
you have an NVIDIA GPU, add a `gpus: all` deploy reservation to the `ollama`
service to make it far faster.

---

## Configuration

All settings come from the environment; defaults live in `application.yaml`.

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `BOT_TOKEN` | yes | — | token from BotFather |
| `BOT_USERNAME` | yes | — | bot username, no `@` |
| `ALLOWED_CHAT_IDS` | yes | — | comma-separated group ids; others are ignored |
| `DB_USER` / `DB_PASSWORD` | yes | — | PostgreSQL credentials |
| `DB_URL` | in compose | set by compose | JDBC URL |
| `ADMIN_USER_IDS` | no | empty | who may run admin commands; **empty means every member of an allowed chat** |
| `POLL_INTERVAL_MINUTES` | no | `30` | how often sources are polled |
| `MAX_ITEMS_PER_TOPIC` | no | `5` | news messages per topic per cycle |
| `NEWS_LOOKBACK_HOURS` | no | `24` | how old an article may be and still count as fresh |
| `TOPIC_PREFIX` | no | `news-` | prefix marking the bot's topics |
| `BOOTSTRAP_TOPICS` | no | empty | subjects created on start-up |
| `WELCOME_PIN` | no | `true` | pin the welcome message |
| `TOPIC_PROFILE` | no | `true` | let the model read an ambiguous topic name once and say what it is about |
| `SEND_RATE_PER_MINUTE` | no | `17` | outbound throttle (Telegram allows ~20/min per group) |
| `LINK_PREVIEW` | no | `FIRST_ONLY` | `FIRST_ONLY` \| `ALWAYS` \| `NEVER` |
| `SEEN_RETENTION_DAYS` | no | `30` | how long a sent article is remembered |
| `FILTER_AI_CHECK` | no | `true` | let the model judge an article by meaning, not only by word |
| `FILTER_MAX_TERMS_PER_TOPIC` | no | `30` | how many filter terms one topic may hold |
| `SUMMARIZER` | no | `ollama` | `ollama` \| `anthropic` \| `extractive` |
| `GOOGLE_NEWS_DECODE` | no | `true` | decode a Google News stub to the publisher URL |
| `ARTICLE_CONTEXT` | no | `true` | read the article page when the feed description is too thin to summarise |
| `ARTICLE_CONTEXT_MIN_DESCRIPTION_CHARS` | no | `200` | a feed description shorter than this triggers that read |
| `ARTICLE_CONTEXT_MAX_CHARS` | no | `1500` | upper bound on the text handed to the model |
| `OLLAMA_MODEL` | no | `llama3.2:3b` | model pulled into the ollama container |
| `OLLAMA_BASE_URL` | no | `http://ollama:11434` | set by compose |
| `OLLAMA_TIMEOUT_SECONDS` | no | `120` | how long one summary may take |
| `LLM_API_KEY` / `LLM_MODEL` | no | — / `claude-opus-5` | for `SUMMARIZER=anthropic` |
| `LLM_MAX_CALLS_PER_HOUR` | no | `100` | hosted-LLM budget; over it, extractive is used |
| `JOURNALIST_PORT` | no | `8080` | host port for `/actuator/health`; change it when 8080 is taken |
| `LOG_LEVEL` | no | `INFO` | |

Running the jar outside Docker without an Ollama server is fine: every call fails
over to the extractive summariser and the bot keeps working. Set
`SUMMARIZER=extractive` to silence the warnings.

---

## Development

```bash
cd app
./gradlew build                              # compile + 90 offline tests
./gradlew test                               # tests only, no network, no Docker
INTEGRATION_TESTS=true ./gradlew test        # 105 tests, incl. a real PostgreSQL
```

Integration tests use an embedded PostgreSQL server (no Docker). The first run
downloads the server binary, which is why they are opt-in.

Layout:

```
com.dach.sagiri
├── telegram/   update handling, command routing, the outbound queue
├── topic/      the topic registry, provisioning, welcome messages
├── news/       providers (RSS, Google News) and the aggregator
├── summary/    extractive and optional LLM summarisers
├── dedup/      canonical URLs, fingerprints, seen articles
├── schedule/   the polling job and retention
├── health/     the polling health indicator
└── config/     properties, presets, message templates
```

`docs/SPEC.md` is the full specification, `DECISIONS.md` records where the
implementation departs from it and why, `PROGRESS.md` tracks the stages.

### Continuous integration

Three workflows run on every push and pull request, on every branch:

| Workflow | File | What it runs |
|---|---|---|
| Gradle Build | `.github/workflows/gradle-build.yml` | `./gradlew clean build -x test` in `app/`; on `master` it keeps the boot jar as an artifact |
| Gradle Test | `.github/workflows/gradle-test.yml` | `./gradlew clean test` offline, uploads the test and JaCoCo reports |
| Docker | `.github/workflows/docker.yml` | `docker compose config` against a `.env` seeded from `.env.example`, plus a build of the bot image |

Integration tests stay out of CI: they are opt-in behind `INTEGRATION_TESTS=true`.
Each workflow runs with `contents: read` only, and a branch with an open pull
request builds once rather than twice.

`.github/dependabot.yml` opens weekly update PRs for the Gradle dependencies in
`/app`, the GitHub Actions and the `Dockerfile` base images.

---

## Troubleshooting

**The bot ignores commands in the group.** Privacy mode is still on. BotFather →
`/setprivacy` → Disable, then remove and re-add the bot.

**Replies land in General instead of the topic.** The message was sent in General;
General genuinely has no `message_thread_id`.

**`/newtopic` answers "failed to create".** The bot is not an administrator, or
lacks `can_manage_topics`.

**The welcome message is not pinned.** Missing `can_pin_messages`. The topic works
regardless; the log carries a warning.

**A topic created before the bot joined gets nothing.** Expected — Bot API cannot
list existing topics. Send `/bind news-<subject>` inside it.

**No news arrives.** Check `/status`, then `/now` inside the topic. An empty result
usually means nothing was published in the last `NEWS_LOOKBACK_HOURS`, or every
candidate was already sent. `docker compose logs -f bot` shows per-feed warnings.

**Health is DOWN.** No successful polling cycle within twice the interval. The
details of `/actuator/health` show when the last one was.

**Migrations fail on an existing database.** Flyway never rewrites an applied
migration. Fix forward with a new `V2__*.sql`.

**Summaries look like raw feed text.** The model is not answering. Check
`docker compose logs ollama-init` — on a first run the pull may still be going.
The bot logs `Model ... is not available on the Ollama server yet` or
`Ollama is unreachable` in that case; both are handled by the fallback.

**The ollama container keeps restarting or is OOM-killed.** The model does not fit
the 3 GB limit in `docker-compose.yml`. Raise it, or pick a smaller
`OLLAMA_MODEL`.

---

## Security notes

- No secrets in the repository; `.env` is git-ignored and `.env.example` is empty.
- `ALLOWED_CHAT_IDS` is mandatory — without it the bot would answer in any group
  it is added to.
- Give the development bot its own token and its own test group. Never hand a
  production token to a coding agent.
- `app/src/main/resources/config.properties` is a git-ignored local-dev fallback
  for `bot.key`. If it holds a real token, revoke it via BotFather.
- That file is also excluded from the Docker build context by `app/.dockerignore`,
  so it never reaches the jar or the image. The compose build context is `./app`,
  which is why that file - not the repository-root `.dockerignore` - is the one
  that matters.
