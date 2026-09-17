# Working rules

- **Never commit and never push.** No `git commit`, `git push`, `git merge`,
  `git rebase`, no amending, no `--no-verify`. Stage only: `git add <paths>`,
  then report what is staged and offer a conventional commit message as text.
  The commit itself is the user's action. Same for tags and branches: propose,
  do not create.
- **Keep the project structure.** New code goes into the existing package
  `com.dach.sagiri.<feature>` next to the code it belongs with; a test lives in
  the mirrored path under `app/src/test/java/...`. Do not add modules, source
  sets, top-level directories or new layers ("service/impl", "util") without
  asking first. Prefer extending an existing class over creating a parallel one.
- **Minimum comments.** The code carries the meaning: clear names, small
  methods, no comment that restates the line below it, no Javadoc boilerplate on
  obvious members, no commented-out code, no `// TODO` left behind. A short
  comment is allowed only for a non-obvious *why* — a Telegram API quirk, a
  race, a deliberate deviation from `docs/SPEC.md`.
- **New functionality ships with unit tests.** JUnit 5 + AssertJ + Mockito
  (`mock`/`when`/`verify`) for every collaborator; no Spring context, no network,
  no Docker in a unit test — `./gradlew test` must pass offline. Cover the happy
  path, the failure/fallback path and the boundary case that motivated the code.
  Integration tests stay behind `INTEGRATION_TESTS=true`.
- Small, focused changes; do not reformat or "clean up" files the task does not
  touch.
