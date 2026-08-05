# Antigenic Fix Brief: Generic Signal

## Context

Signal: `{signalType}`  
Source: `{source}`  
Task ID: `{taskId}`  
Message: `{message}`

## Objective

Investigate the antigenic signal above and fix the underlying issue.

## Process

1. Load `superpowers:systematic-debugging`.
2. Identify the root cause using the 4-phase method.
3. Write a regression test that reproduces the condition.
4. Implement the minimal fix.
5. Run the antigenic-related unit tests offline:
   ```bash
   JAVA_TOOL_OPTIONS='-Djava.net.preferIPv6Addresses=true' ./gradlew :app:testDebugUnitTest \
     --tests 'com.example.data.AntigenicSignalTest' \
     --tests 'com.example.data.AntigenicSignalStoreTest' \
     --tests 'com.example.data.AntigenicDispatcherTest' \
     --tests 'com.example.data.AntigenicOrchestratorTest' \
     --tests 'com.example.data.AgenticActionExecutorHeadlessApprovalTest' \
     --tests 'com.example.data.SwarmEngineBudgetGuardrailTest' \
     --console=plain --offline
   ```
6. Report: file paths changed, root cause, and verification result.
