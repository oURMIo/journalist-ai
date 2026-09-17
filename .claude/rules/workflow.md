# How to work on a task here

## 1. Orient before touching code

| Question | File |
|---|---|
| What is this supposed to do? | `docs/SPEC.md` (the numbered section, not the whole file) |
| Why does the code disagree with the spec? | `DECISIONS.md` |
| Is this stage already done? | `PROGRESS.md` |
| Which invariants must not break? | `CLAUDE.md`, `docs/SPEC.md` §15 |

The repository wins over the spec (§0). If the code and `docs/SPEC.md` disagree
and `DECISIONS.md` is silent, that is an ambiguity: stop and ask.

## 2. Plan the change in one paragraph

Name the classes you will touch, the package they live in, and the tests you
will add. If the plan needs a new package, a new module or a new layer — ask
first (`.claude/rules/working.md`).

Read the whole class before editing it. Prefer extending an existing class over
creating a parallel one: `TelegramSender`, `CommandRouter`, `ArticleFilter`,
`TopicRegistryService` and `NewsPollingJob` are the seams the design expects.

## 3. Implement in small steps

- One behaviour per change; do not reformat untouched code.
- User-facing text goes to `messages.yml` and a field on `MessageTemplates` —
  never a string literal in Java.
- New settings go to `BotProperties` + `application.yaml` + `.env.example`,
  bound from the environment, with a default that keeps `docker compose up`
  working.
- Schema changes are a new `V<n>__*.sql`; applied migrations are immutable.
- New outbound Telegram call → through `TelegramSender`, with the thread id.

## 4. Test it (mandatory, same change)

Delegate to the **test-engineer** agent, or follow the same contract yourself:
JUnit 5 + AssertJ + Mockito, every collaborator mocked, no Spring context, no
network, no Docker. Happy path + failure/fallback path + the boundary case that
motivated the code. Anything needing a database or a live service goes behind
`@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")`.

Run it:

```bash
cd app && ./gradlew test          # must be green offline
cd app && ./gradlew build         # before declaring the task done
INTEGRATION_TESTS=true ./gradlew test   # when repositories or migrations changed
```

On Windows use `app\gradlew.bat` from PowerShell, or `./gradlew` from the Bash
tool. `make test` and `make build` do the same thing.

## 5. Review it

Run the **code-reviewer** agent on the diff before staging. Fix what it finds,
or record the deliberate deviation in `DECISIONS.md` with a reason.

## 6. Close the loop

- New assumption or deviation → a numbered section in `DECISIONS.md`.
- A stage or acceptance criterion moved → `PROGRESS.md`, including the test
  counts in its table.
- New command, setting or env var → `README.md` and `.env.example`.
- Then `git add <paths>` and report the staged files plus a conventional commit
  message as text. Never commit, never push.

## Definition of done for any change

1. `./gradlew build` is green offline.
2. New behaviour has unit tests with mocks; the fallback path is one of them.
3. No secret, chat id or token in the diff; `.env` untouched.
4. `docker compose config` still validates and the container still starts with
   the documented environment.
5. `docs/SPEC.md` §15 checklist items that the change touches are still true.
6. Docs updated, changes staged, commit message proposed.

## Reporting

Say what you verified by running it and what you did not. "Tests pass" means the
command was executed and its output read. Anything not executed (Docker, a live
Telegram session, the Ollama model pull) is reported as not verified — the same
distinction `PROGRESS.md` already makes.
