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
| `/closetopic` | admin | inside a topic | close it and deactivate it |
| `/status` | admin | anywhere | active topics, last poll, counters |
| `/now` | admin | inside a topic | poll this topic immediately |
| `/topics` | admin | anywhere | dump the registry |

Unknown commands are ignored without a reply.

---

## Sources

- **Presets** (`app/src/main/resources/topic-presets.yml`) — curated feeds for
  known subjects such as `it`, `science`, `ai`, `space`, `security`.
- **Google News search RSS** — the fallback for everything else. This is what
  makes an arbitrary new subject work with no configuration.

A preset improves quality; it is never a precondition. Add your own by editing
`topic-presets.yml` and rebuilding.

---

## Summaries

The recap under each headline comes from one of three backends, chosen with
`SUMMARIZER`:

| Value | What it does | Cost |
|---|---|---|
| `ollama` (default) | a local model in the `ollama` container writes 1–3 sentences | free, runs on your hardware |
| `anthropic` | hosted Claude, needs `LLM_API_KEY` | paid per article |
| `extractive` | no model: the first sentences of the feed description | free |

Whatever the choice, **only the headline and the feed description are sent to the
model** — the full article is never fetched. Summaries are cached per article, so
the same story is never summarised twice. Any failure, timeout or missing model
falls back to the extractive path, so a slow or absent model never leaves a topic
empty.

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
| `SEND_RATE_PER_MINUTE` | no | `17` | outbound throttle (Telegram allows ~20/min per group) |
| `LINK_PREVIEW` | no | `FIRST_ONLY` | `FIRST_ONLY` \| `ALWAYS` \| `NEVER` |
| `SEEN_RETENTION_DAYS` | no | `30` | how long a sent article is remembered |
| `SUMMARIZER` | no | `ollama` | `ollama` \| `anthropic` \| `extractive` |
| `OLLAMA_MODEL` | no | `llama3.2:3b` | model pulled into the ollama container |
| `OLLAMA_BASE_URL` | no | `http://ollama:11434` | set by compose |
| `OLLAMA_TIMEOUT_SECONDS` | no | `120` | how long one summary may take |
| `LLM_API_KEY` / `LLM_MODEL` | no | — / `claude-opus-5` | for `SUMMARIZER=anthropic` |
| `LLM_MAX_CALLS_PER_HOUR` | no | `100` | hosted-LLM budget; over it, extractive is used |
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
