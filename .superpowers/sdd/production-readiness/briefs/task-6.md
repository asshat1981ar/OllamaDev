# Task 6: Room migration safeguard (Tier 6.4)

## Task Description

`AppDatabase` uses `fallbackToDestructiveMigration(true)` — a Room version bump
wipes user data. Safeguard + document the migration policy.

## Files

- Create: `/home/dev/OllamaDev/docs/adr/ADR-0002-room-migration-policy.md`
- Modify (if needed): `/home/dev/OllamaDev/app/src/main/java/com/example/data/AppDatabase.kt` (or `Daos.kt`), optionally `/home/dev/OllamaDev/app/src/main/java/com/example/MainActivity.kt`

## Acceptance

- An ADR exists documenting the policy: a Room schema bump MUST ship an explicit
  `MIGRATION_*` (e.g. the existing `MIGRATION_13_14` pattern); destructive
  fallback is qualified/removed or made explicit-with-export-note.
- If a code change is made it must be minimal and compile. Only change Room
  `version` if an actual schema change is intentionally included — otherwise
  the ADR + (if retained) a startup data-export note in MainActivity is enough.
- `./gradlew :app:compileDebugKotlin` green (background, poll log).

## Steps

1. Read `AppDatabase.kt` (Room version, `fallbackToDestructiveMigration`, existing `MIGRATION_13_14`).
2. Write `docs/adr/ADR-0002-room-migration-policy.md` (context, decision, consequences; follow the style of `docs/adr/ADR-0001-*.md`).
3. Implement the chosen policy with the MINIMAL code change (AD only, or AD + startup export note in MainActivity if destructive is retained).
4. Compile: `cd /home/dev/OllamaDev && nohup ./gradlew :app:compileDebugKotlin --console=plain >/tmp/t6.log 2>&1 &` then poll until done.
5. Commit: `chore(db): document migration policy + safeguard (tier 6.4)` (only these files).

## Context

- Repo: /home/dev/OllamaDev, branch main. Room version is 14 (`MIGRATION_13_14` exists).
- Never change gradle.properties tuning. JAVA_HOME unset = OpenJDK 17.
