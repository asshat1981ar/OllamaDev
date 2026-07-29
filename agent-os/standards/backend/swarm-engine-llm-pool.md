# SwarmEngine: Single Ollama-Pool Entry Point

`SwarmEngine.generateFromFallbackPool()` (`app/src/main/java/com/example/data/SwarmEngine.kt`)
is the single entry point for every LLM call — agent-persona generation, consensus-vote
scoring, and any other freeform system prompt. There is no cloud/Gemini fallback; a prior
`GeminiService.kt` was removed as unused dead code, not as a deliberate no-cloud stance.

Selection logic:
1. Filter Ollama nodes to `status == "Online"`.
2. If a `preferredModelName` is given, prefer the lowest-latency online node whose
   `availableModels` matches it.
3. Otherwise fall back to the lowest-latency online node running any model.
4. If no node is online, return an error string telling the user to configure one in
   Manage > Nodes — there's no other fallback path.

- **How to apply:** Route new LLM-calling features (agent work, chat replies, scoring,
  summarization, etc.) through `generateFromFallbackPool()` / `generateFreeform()`, not a
  new direct `OllamaService` call — that's how node selection and streaming stay
  consistent across the app.
- Re-adding a cloud fallback is not against a project rule; it just doesn't exist today.
