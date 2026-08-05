# Antigenic Multi-Agent Workflow Research Report

**Research Date:** 2026-07-30  
**Subagent ID:** 20260730_5  
**Scope:** Survey antigenic/immune-system/self-correcting multi-agent workflow patterns for OllamaDev Tier-6 orchestration

---

## Executive Summary

The "antigenic" pattern in OllamaDev models biological immune systems: detect threats (anomalies, failures, overruns), classify them, and dispatch specialized responders. This report surveys five key areas: (1) self-correcting agent loops, (2) red-teaming/adversarial review, (3) budget/cost guardrails, (4) delegation hierarchies (MetaGPT, ChatDev, AutoGen), and (5) failure taxonomy and recovery patterns. Finally, we propose a concrete Antigenic Signal Taxonomy tailored to OllamaDev's Android agentic coding harness.

---

## 1. Self-Correcting Agent Loops

### 1.1 Reflexion (Shinn et al., 2023)
**Paper:** *Reflexion: Self-Reflective Agents with Verbal Reinforcement Learning*

**Mechanism:**
- The Actor (LLM) attempts a task
- The Evaluator computes a reward/score (heuristic or LLM-based)
- The Self-Reflection component generates a natural language critique stored in Episodic Memory
- The next trial loads prior reflections to improve performance

**Key Insight:** Reflection is stored as *text* (natural language) in memory, not as gradients. This makes it transferable across tasks and model instances.

**OllamaDev Applicability:**
- Store `[UNRESOLVED]` task reflections in `TaskStep` output field
- Feed prior sprint cycle artifacts as context to the next cycle (see `SprintOrchestrator.distilArtifact()`)

### 1.2 Self-Debugging (Chen et al., 2023)
**Mechanism:**
- LLM explains code execution traces
- Predicts error location and cause
- Suggests minimal fixes

**OllamaDev Applicability:**
- When `SwarmEngine` halts with `[UNRESOLVED]`, use a specialized "Debugger" agent subagent
- Parse stack traces from MCP sandbox tool results

### 1.3 ReAct + Verify (Yao et al., 2022)
**Mechanism:**
- Reasoning → Action → Verification loop
- Verification step *gates* continuation (unlike ReAct alone where action proceeds regardless)

**OllamaDev Mapping:**
- `AGENTIC_LOOP` already implements this: Plan → Act → Verify
- The "verification" phase should produce a pass/fail signal that can trigger antigenic response

---

## 2. Red-Teaming / Adversarial Review Agents

### 2.1 LLM-as-Judge Pattern
**Source:** Perez & Ribeiro, 2022; GlaDOS benchmark

**Approach:**
- Separate "judge" LLM evaluates outputs of the primary agent
- Can be rule-based (keyword matching) or model-based (LLM critique)

**OllamaDev Applicability:**
- After `WRITE_FILE:` directive execution, a "reviewer" agent could check for:
  - Kotlin syntax errors
  - Pattern violations (e.g., `Icons.Rounded.*` vs wrong icon set)
  - Security issues (hardcoded keys)

### 2.2 Constitutional AI (Anthropic)
**Mechanism:**
- Critique → Revision loop
- Critique model evaluates output against constitutional principles
- Revision model rewrites to satisfy critique

**OllamaDev Applicability:**
- Define "constitution" as `CLAUDE.md` + `agent-os/standards/`
- On detecting `[UNRESOLVED]` or style violation, dispatch critique subagent

### 2.3 Multi-Agent Debate (Du et al., 2023)
**Mechanism:**
- Multiple agents with different roles debate a solution
- Judge or consensus mechanism selects final output

**OllamaDev Applicability:**
- For critical architectural decisions (new Room entities, API changes)
- Have "architect" vs "security" agents debate before committing

---

## 3. Budget/Cost Guardrails as Immune Signals

### 3.1 Cost-Aware Routing
**Mechanism:**
- Track cumulative token spend per task
- Switch to cheaper model (local Ollama) when approaching cap
- Record `BUDGET_HALT` TaskStep when exceeded

**OllamaDev Status:** Already implemented in `TaskBudgetTracker`

### 3.2 Circuit Breaker Pattern
**Source:** Michael Nygard, *Release It!*

**Mechanism:**
- After N failures (MCP timeouts, git errors), "open" the circuit
- Fail fast instead of retrying
- Periodically probe to see if service recovered

**OllamaDev Applicability:**
- If `McpClient` fails 3 times in a row, mark node as unhealthy
- Route to fallback pool immediately without waiting for timeouts

### 3.3 Tiered Fallback Hierarchy
**Mechanism:**
```
Cloud (fast, expensive) → Local (slower, free) → Pause (wait for user)
```

**OllamaDev Applicability:**
- Already partially implemented in `generateFromFallbackPool`
- Antigenic signal: `BUDGET_OVERRUN` triggers automatic flip to `preferCloud=false`

---

## 4. Delegation-and-Review Hierarchies

### 4.1 MetaGPT (OpenBMB)
**Architecture:**
- Roles: Product Manager, Architect, Project Manager, Engineer, QA Engineer
- Communication via structured documents (PRD, design docs)
- SOP (Standard Operating Procedure) defines handoffs

**Key Idea:** Documents are shared memory; role transitions are explicit

**OllamaDev Mapping:**
- `SprintOrchestrator` phases map to MetaGPT SOPs:
  - `PLAN` → Product Manager
  - `DESIGN` → Architect  
  - `IMPLEMENT` → Engineer
  - `VERIFY` → QA Engineer
  - `REFLECT` → Project Manager

### 4.2 ChatDev (OpenBMB)
**Architecture:**
- Roles: CEO, CTO, Programmer, Reviewer, Tester, Designer
- "Chat chain" waterfall: multi-turn conversation per phase
- Phase transitions via natural language consensus ("I think we're done with design")

**Key Idea:** Simulate entire software company via chat

**OllamaDev Mapping:**
- Current `AGENTIC_LOOP` is already chat-based
- Gap: ChatDev has explicit "review" and "tester" agents; OllamaDev currently has single QA role

### 4.3 AutoGen (Microsoft)
**Architecture:**
- Conversable agents with customizable conversation patterns
- GroupChatManager handles speaker selection
- UserProxy agent for human-in-the-loop

**Key Idea:** Flexible orchestration via `select_speaker()`, `resume()`

**OllamaDev Mapping:**
- `PendingApprovalStore` is OllamaDev's UserProxy equivalent
- Could extend to support multi-agent group chat for complex decisions

### 4.4 CrewAI
**Architecture:**
- Agent + Tools + Tasks model
- Process-based: sequential or hierarchical
- Role-based with tool access

**Key Idea:** Process definition is declarative (YAML/config)

**OllamaDev Applicability:**
- Current `AGENTIC_LOOP` is hardcoded; could become configurable

---

## 5. Failure Taxonomy and Recovery Patterns

### 5.1 Five-Level Failure Hierarchy

| Level | Examples | Recovery Strategy |
|-------|----------|-------------------|
| **Tool** | MCP_TIMEOUT, GIT_FAILURE, SANDBOX_ERROR | Retry → Fallback tool → Delegate fix subagent |
| **Agent** | PARSE_ERROR, INVALID_DIRECTIVE, [UNRESOLVED] | Re-prompt → Constraint tighten → Human handoff |
| **Budget** | TOKEN_CAP_EXCEEDED, COST_LIMIT | Fallback to local → Pause → Surface to user |
| **Approval** | APPROVAL_DECLINED, APPROVAL_SKIPPED_HEADLESS | Record skip → Surface in notification → Auto-policy |
| **System** | DB_ERROR, NETWORK_ERROR, SERVICE_CRASH | Retry backoff → Circuit breaker → Human alert |

### 5.2 Recovery Patterns

**Retry with Exponential Backoff:**
- Max 3 retries for transient failures (network, MCP timeout)
- Delay: 1s, 2s, 4s

**Graceful Degradation:**
- If cloud node fails, fall back to local
- If local fails, pause and surface to user

**Subagent Delegation:**
- For complex fixes (test failures, verification errors), dispatch specialized subagent
- Subagent has narrow scope: single bug, single file, single test

---

## 6. Proposed Antigenic Signal Taxonomy for OllamaDev

### 6.1 Signal Classification

| Signal | Severity | Category | Source | Auto-Response |
|--------|----------|----------|--------|---------------|
| `BUDGET_OVERRUN` | CRITICAL | BUDGET | TaskBudgetTracker | Flip `preferCloud=false`, record signal, continue |
| `MCP_RISKY_CALL` | WARNING | SAFETY | AgenticActionExecutor | Gate on approval; if headless: auto-decline + signal |
| `APPROVAL_DECLINED` | WARNING | SAFETY | PendingApprovalStore | Record skip, mark todo `[APPROVAL_DECLINED]`, surface |
| `APPROVAL_SKIPPED_HEADLESS` | CRITICAL | SAFETY | AgenticLoopService | Auto-decline, record TaskStep, surface in notification |
| `VERIFICATION_UNRESOLVED` | WARNING | QUALITY | SwarmEngine | Record `[UNRESOLVED]`, increment `reimplCount`, loop |
| `TEST_FAILURE` | CRITICAL | QUALITY | MCP Sandbox | Record signal, delegate fix subagent |
| `GIT_ERROR` | WARNING | INTEGRATION | GitService | Retry → Record signal → Delegate fix subagent |
| `MCP_TIMEOUT` | WARNING | TOOL | McpClient | Retry with backoff → Record signal → Fallback node |
| `PARSE_ERROR` | WARNING | AGENT | SwarmEngine | Re-prompt with constraint → Record if repeated |
| `HALT_ITERATIONS_EXCEEDED` | CRITICAL | QUALITY | SwarmEngine | Pause cycle, surface to user |

### 6.2 AntigenicSignal Data Model

```kotlin
enum class AntigenicSeverity { INFO, WARNING, CRITICAL }

enum class AntigenicCategory {
    BUDGET,        // Cost/token overruns
    SAFETY,        // Approval gates, risky operations
    QUALITY,       // Verification failures, test failures
    TOOL,          // MCP, git, sandbox errors
    AGENT,         // Parse errors, invalid directives
    INTEGRATION,   // DB, network, service errors
}

data class AntigenicSignal(
    val id: Long = 0,
    val taskId: Int? = null,
    val cycleId: Int? = null,
    val severity: AntigenicSeverity,
    val category: AntigenicCategory,
    val source: String,           // Class/component that emitted
    val signalType: String,       // BUDGET_OVERRUN, etc.
    val message: String,          // Human-readable
    val detail: String = "",      // Machine-parseable context (JSON)
    val detectedAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
    val resolverReportPath: String? = null,
    val resolution: String? = null, // How it was resolved
)
```

### 6.3 AntigenicOrchestrator Responsibilities

**Detection:**
- Observe `AntigenicSignalStore.unresolvedSignals` via StateFlow
- Poll or reactive: new signal triggers dispatch

**Classification:**
- Map `signalType` to `AntigenicStrategy`

**Response Strategies:**

| Strategy | When | Action |
|----------|------|--------|
| `SURFACE_TO_USER` | WARNING safety issues | Update notification, show badge in UI |
| `PAUSE_CYCLE` | CRITICAL quality, HALT | Stop `SprintOrchestrator`, wait for user |
| `DELEGATE_FIX_SUBAGENT` | TEST_FAILURE, GIT_ERROR, BUDGET_OVERRUN | Dispatch subagent via `delegate` tool with brief |
| `AUTO_FALLBACK` | BUDGET_OVERRUN | Flip flag, record signal, continue (no pause) |
| `RECORD_AND_CONTINUE` | INFO, non-blocking | Log to store, continue execution |

### 6.4 Subagent Brief Templates

For each signal type, a brief template in `.superpowers/sdd/antigenic-tier6-workflow/briefs/`:

- `budget-fallback.md` — Make AGENTIC_LOOP fall back to local nodes on cap exceeded
- `headless-approval.md` — Implement safe default for headless risky directives
- `verify-prompt-regression.md` — Lock verify-prompt construction, add regression test
- `test-failure-fix.md` — Debug and fix failing test
- `generic-fix.md` — Systematic debugging for unknown failures

### 6.5 Integration Points

**Existing Components to Instrument:**

1. **TaskBudgetTracker.haltIfOverBudget()** → Emit `BUDGET_OVERRUN` before halt
2. **AgenticActionExecutor.executeAgenticMcpCall()** → Emit `MCP_RISKY_CALL` when gated
3. **AgenticActionExecutor.executeAgenticGitCommand()** → Emit `APPROVAL_DECLINED` on reject
4. **AgenticLoopService** → Emit `APPROVAL_SKIPPED_HEADLESS` for headless risky calls
5. **SwarmEngine.runAgenticLoopWorkflow()** → Emit `VERIFICATION_UNRESOLVED` on `[UNRESOLVED]`
6. **McpClient.call()** → Emit `MCP_TIMEOUT` on timeout/failure
7. **GitService** → Emit `GIT_ERROR` on push/commit failure

**New Components:**

1. **AntigenicSignalStore** — Singleton, StateFlow of unresolved signals
2. **AntigenicOrchestrator** — Observes store, dispatches strategies
3. **AntigenicPanel** — (Optional) UI component showing active signals

### 6.6 Cycle Protocol Integration

Per `agent-os/plans/tier6-cycle-log.md`, antigenic responses feed the feed-forward contract:

```
Cycle N: Detect signal → Delegate fix → Record resolution
Cycle N+1: Synthesis subagent reads antigenic reports, updates backlog
```

The `SprintOrchestrator` should:
1. Check `AntigenicSignalStore.unresolvedSignals` at phase boundaries
2. If CRITICAL signals exist, pause before next phase
3. On completion, include antigenic summary in `SprintArtifact`

---

## 7. References

### Papers
1. Shinn et al. (2023). *Reflexion: Self-Reflective Agents with Verbal Reinforcement Learning*
2. Chen et al. (2023). *Teaching Large Language Models to Self-Debug*
3. Yao et al. (2022). *ReAct: Synergizing Reasoning and Acting in Language Models*
4. Perez & Ribeiro (2022). *Red Teaming Language Models with Language Models*
5. Du et al. (2023). *Improving Factuality and Reasoning in Language Models through Multiagent Debate*
6. Bai et al. (2022). *Constitutional AI: Harmlessness from AI Feedback* (Anthropic)

### Open Source
1. [AutoGen](https://github.com/microsoft/autogen) — Microsoft
2. [MetaGPT](https://github.com/geekan/MetaGPT) — OpenBMB
3. [ChatDev](https://github.com/OpenBMB/ChatDev) — OpenBMB
4. [CrewAI](https://github.com/joaomdmoura/crewAI) — João Moura
5. [LangGraph](https://www.langchain.com/langgraph) — LangChain

### Patterns
1. Nygard, Michael. *Release It!* (Circuit Breaker, Bulkhead patterns)
2. Hohpe & Woolf. *Enterprise Integration Patterns* (Message Channel, Dead Letter)

---

## 8. Conclusion

The antigenic pattern provides OllamaDev with a systematic way to handle the inevitable failures and edge cases in autonomous coding. By classifying signals by severity and category, and mapping them to appropriate response strategies (user surface, pause, or subagent delegation), the system becomes self-healing rather than brittle. The taxonomy proposed here is grounded in both academic research (Reflexion, Constitutional AI) and practical open-source implementations (MetaGPT, AutoGen).

**Immediate Next Steps:**
1. Implement `AntigenicSignal` data class and `AntigenicSignalStore` singleton
2. Instrument existing detection points (TaskBudgetTracker, AgenticActionExecutor, SwarmEngine)
3. Implement `AntigenicOrchestrator` with strategy mapping
4. Create subagent brief templates for high-frequency signals
5. Execute first antigenic cycle on backlog item 6.3 (headless approval)

---

*Report generated by subagent 20260730_5*
