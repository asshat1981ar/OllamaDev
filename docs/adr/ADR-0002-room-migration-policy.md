# ADR-0002: Room schema migration policy

- Status: Accepted
- Date: 2026-07-30

## Context

`AppDatabase` (Room, `version = 14`) is built with
`fallbackToDestructiveMigration(true)`. When Room detects a schema-version bump for
which no `MIGRATION_*` object is registered, it responds by **dropping and recreating
every table** — i.e. a missed migration silently wipes all locally persisted Ollama
Swarm data (nodes, agents, swarm configs/tasks, step rows, workspace files, chat
history, git commits, MCP servers, skills, sprint cycles/artifacts).

The database currently ships exactly one explicit migration, `MIGRATION_13_14`
(13 -> 14), which creates the `sprint_cycles` and `sprint_artifacts` tables. Any
future bump without a matching `MIGRATION_*` would destroy user data instead of
preserving or erroring.

Without a policy, the destructive fallback reads as an accidental default rather than
a deliberate decision, and nothing tells a future engineer that a schema bump must
also ship a migration.

## Decision

Adopt the following Room migration policy:

1. **A schema-version bump MUST ship an explicit `MIGRATION_*` object** and register it
   via `addMigrations(...)`, following the existing `MIGRATION_13_14` pattern
   (hand-written SQL, `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE` / data-copy as
   needed). `version` may only be incremented as part of a change that also adds the
   corresponding migration.

2. **The destructive fallback is retained, but made explicit and qualified** as a
   documented last-resort safety net. `fallbackToDestructiveMigration(true)` stays in
   `AppDatabase.getDatabase(...)` with an inline comment cross-referencing this ADR.
   Rationale: removing it turns a missed migration into a hard `IllegalStateException`
   on every app launch with no recovery path, which is worse for a user than a
   documented, one-time destructive reset. The fallback is therefore the *qualify*,
   not *remove*, option chosen here.

3. **A startup data-export note is logged** in `MainActivity.onCreate` when the app
   boots, so the destructive-fallback risk is visible to operators before a schema
   upgrade: if the fallback ever fires, local data is wiped, so user data should be
   exported/backed up before upgrading.

4. **Schema export is on the roadmap**, not this change: `exportSchema = false` today.
   Enabling schema export would let the schema diff feed future migration generation;
   tracked separately rather than bundled into this safeguard.

## Consequences

- Data-preserving upgrades are now the required, documented path: every bump must
  carry a `MIGRATION_*` (the `MIGRATION_13_14` pattern is the template).
- The destructive fallback is no longer an accidental default: it is an explicit,
  commented, ADR-referenced safety net, and its data-wiping behaviour is surfaced at
  startup via a log note.
- A future engineer bumping `version` without a migration is flagged by the policy
  (and, if schema export is enabled later, by schema-diff tooling).
- Trade-off accepted: if a migration is missing/broken, the app recovers by wiping
  local data rather than crashing on every launch. This is documented so the cost
  (data loss) is a conscious decision, not a surprise.

## Alternatives considered

- **Remove `fallbackToDestructiveMigration(true)`.** Rejected: a missed migration then
  throws `IllegalStateException` on open, bricking the app on every launch with no
  recovery, which is a worse failure mode than a documented destructive reset.
- **Do nothing (keep the implicit destructive fallback).** Rejected: leaves the
  data-wiping behaviour undocumented and lets a future bump wipe data by accident.
- **Enable `exportSchema` + auto-generated migrations.** Deferred: a larger change
  (Room schema JSON on disk, migration generation) not required for this safeguard;
  captured as a consequence/roadmap item.