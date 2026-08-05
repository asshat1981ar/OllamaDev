# Task 7 Report: Budget heuristic documentation (Tier 6.5)

## Change summary

Added concise explanatory copy to the Budget UI info card in
`app/src/main/java/com/example/ui/BudgetScreen.kt`. The copy now states that:

- Token counts are **estimates** derived from character length
  `((prompt + output) / 2 + 100)`, **not a real tokenizer**, so actual usage may vary.
- The cap is enforced **between loop iterations**; an in-progress LLM call is
  allowed to finish and may exceed the cap.
- Cap **0 = legacy unlimited** behavior.

The previous copy only mentioned the loop stopping at the cap and "0 disables the
guardrail"; it did not explain the estimate nature or the formula. The new text
preserves the existing UI tone and stays within the existing single `Text` element
(no layout changes; only the string literal was edited).

## Files changed

- `app/src/main/java/com/example/ui/BudgetScreen.kt` (1 insertion, 1 deletion)

## Compile evidence

Command: `./gradlew :app:compileDebugKotlin --console=plain`

Result:
```
BUILD SUCCESSFUL in 1m 19s
9 actionable tasks: 1 executed, 8 up-to-date
Configuration cache entry reused.
```
Only pre-existing deprecation warnings (SecurePrefs, SwarmViewModel) appeared; no
errors. Full log preserved at `/tmp/t7g.log`.

## Environment note (important for teammates)

The Gradle client cannot connect to a Gradle daemon bound on IPv4 loopback in this
sandbox: Java server sockets on `127.0.0.1` are unreachable from any local client
(Java→Java and Python→Java both refused), while IPv6 loopback (`::1`) works. The
uncommitted `gradle.properties` change (from another task) sets
`-Djava.net.preferIPv4Stack=true`, which drives the daemon onto the broken IPv4 path
→ "Could not connect to the Gradle daemon". Gradle filters `-Djava.net.*` from the
daemon's JVM opts, so editing `gradle.properties` alone did not help and may be why
recent builds fail.

Working workaround used here (no permanent file change):
```
JAVA_TOOL_OPTIONS='-Djava.net.preferIPv6Addresses=true -Djava.net.preferIPv4Stack=false' \
  setsid nohup ./gradlew :app:compileDebugKotlin --console=plain >/tmp/t7g.log 2>&1 < /dev/null &
```
`JAVA_TOOL_OPTIONS` is injected by the JVM launcher itself and is not stripped by
Gradle's daemon-opts sanitization, so the daemon binds `::1` and the client connects
successfully. Run detached with `setsid` so it survives the harness's process-group
teardown, then poll the log.

`gradle.properties` was restored byte-identical to its pre-task state (verified with
`diff` against a backup); its remaining uncommitted diff is the pre-existing
modification from another task, untouched by this work.

## Compile/task self-review

- Copy matches the acceptance criteria: estimate formula, approximate vs real
  tokenizer variance, cap enforced between iterations with in-progress exceptions,
  and cap-0 = unlimited/legacy.
- Only `BudgetScreen.kt` was committed (verified via `git show --stat HEAD`).
- Compile green.
- No layout/behavioral changes; pure documentation string edit.

## Concerns

- The underlying Gradle/IPv6-loopback issue is pre-existing and affects the whole
  repo (all `./gradlew` invocations), not just this task. Recommend flagging the
  `gradle.properties` `preferIPv4Stack` line / the JAVA_TOOL_OPTIONS workaround to
  the team so other tasks can compile reliably.