# Antigenic Fix Brief: Headless Approval Policy

Signal: `APPROVAL_SKIPPED_HEADLESS`
Context: taskId=${taskId}

## Goal
Implement a safe default for risky directives issued from `AgenticLoopService` (no UI available): auto-decline, record an `APPROVAL_SKIPPED_HEADLESS` TaskStep, and surface the skip in the completion notification.

## Constraints
- Re-read `app/src/main/java/com/example/service/AgenticLoopService.kt` and `app/src/main/java/com/example/data/AgenticActionExecutor.kt`.
- Add `isHeadless` detection to the approval request path.
- Add `AgenticActionExecutorHeadlessApprovalTest` covering git push and destructive MCP.
- Do not commit.

## Deliverables
1. Headless policy implemented.
2. Notification summary includes skip count.
3. Test passes.
4. Report to `.superpowers/sdd/antigenic-tier6-workflow/reports/headless-approval-report.md`.
