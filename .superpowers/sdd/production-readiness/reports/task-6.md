# Task 6 Report — Room migration safeguard (Tier 6.4)

**Status: DONE**
**Branch:** main · **Commit:** `9df041b` `chore(db): document migration policy + safeguard (tier 6.4)`

## 1. Policy implemented (chosen option)

Decided the **"make it explicit with an export note"** path (destructive fallback
retained but qualified), rather than removing it:

1. **ADR-0002** written at `docs/adr/ADR-0002-room-migration-policy.md` (context,
   decision, consequences, alternatives — following ADR-0001's style). Core policy:
   a Room schema-version bump **MUST ship an explicit `MIGRATION_*`** registered via
   `addMigrations`, following the existing `MIGRATION_13_14` pattern; `version` may
   only move as part of a change that also adds the matching migration.
2. **`AppDatabase.kt`** — the fallback is qualified in code: `fallbackToDestructiveMigration(true)`
   now carries an inline KDoc-style comment cross-referencing ADR-0002, stating it is a
   *documented last-resort safety net* (a missed/broken migration wipes local data
   instead of crashing on every launch) and that data must be exported/backed up before
   upgrading. **`version = 14` untouched** (no intentional schema change in this task).
3. **`MainActivity.kt`** — startup data-export note: `Log.w("RoomMigration", ...)` in
   `onCreate` surfaces the destructive-fallback risk and the export/backup advice at
   every app boot (ADR-0002 referenced).

Rationale for retain-not-remove: removing the fallback turns a missed migration into a
hard `IllegalStateException` on every launch with no recovery path — worse for a user
than a documented, one-time destructive reset. This is the "qualify" branch of the
acceptance criterion ("destructive fallback is qualified/removed or made explicit-with-export-note").

## 2. Code change (minimal)

- `docs/adr/ADR-0002-room-migration-policy.md` — NEW (73 lines).
- `app/src/main/java/com/example/data/AppDatabase.kt` — +5 lines (qualifying comment);
  no behavior change.
- `app/src/main/java/com/example/MainActivity.kt` — +5 lines (`android.util.Log` import
  + one `Log.w` note in `onCreate`); no behavior change.

Room `version`/`MIGRATION_13_14` untouched, per the task instruction (no schema change included).

## 3. Compile evidence

Command (run in background due to the 30s tool timeout; env invocation is the proven
one from task-8's report, deps cached so no `/tmp/m2repo` bootstrap needed):

```
cd /home/dev/OllamaDev && JAVA_TOOL_OPTIONS='-Djava.net.preferIPv6Addresses=true' \
  setsid nohup ./gradlew :app:compileDebugKotlin --console=plain >/tmp/t6.log 2>&1 </dev/null &
```

Log tail (/tmp/t6.log):

```
w: file:///home/dev/OllamaDev/app/src/main/java/com/example/viewmodel/SwarmViewModel.kt:456:49 Unchecked cast of 'Any?' to 'List<String>'.

BUILD SUCCESSFUL in 1m 30s
9 actionable tasks: 2 executed, 7 up-to-date
Configuration cache entry stored.
```

No `e:` errors; only pre-existing deprecation/unchecked-cast warnings. Compile is GREEN.

## 4. Files changed (committed — nothing else)

```
app/src/main/java/com/example/MainActivity.kt     |  5 ++
app/src/main/java/com/example/data/AppDatabase.kt |  5 ++
docs/adr/ADR-0002-room-migration-policy.md        | 73 +++++++++++++++++++++++
3 files changed, 83 insertions(+)
```

Commit: `9df041b chore(db): document migration policy + safeguard (tier 6.4)`.
Only the three task files were staged; the repo's many other pre-existing
(unrelated) working-tree changes were left untouched and unstaged.

## 5. Self-review

- Acceptance criterion met: ADR documents the explicit-`MIGRATION_*` policy; destructive
  fallback is made explicit-with-export-note (ADR + code comment + startup log in MainActivity).
- Minimal: 10 lines of code change + 1 ADR; no schema/version/builder behavior change;
  `gradle.properties` / `settings.gradle.kts` / build files untouched by me.
- `fallbackToDestructiveMigration(true)` retained deliberately and defensibly
  (crash-on-every-launch is worse than documented destructive reset); documented in
  ADR under "Alternatives considered".
- Compile green before commit; commit diff contains exactly the intended files.

## 6. Concerns

- The destructive fallback still wipes data if someone bumps `version` without a
  migration — now fully documented but not mechanically enforced. Enforcing would need
  `exportSchema = true` + schema-diff CI, tracked in ADR-0002 as a consequence/roadmap item.
- Pre-existing env quirk (WSL IPv4 loopback) requires the `JAVA_TOOL_OPTIONS=...preferIPv6Addresses=true`
  flag for any Gradle JVM; documented in the run-ollamadev skill, used here successfully.
- Repo working tree still contains many unrelated modified/untracked files from other
  in-flight tasks; this commit is scoped strictly to task-6 files.

Report file: `/home/dev/OllamaDev/.superpowers/sdd/production-readiness/reports/task-6.md`
