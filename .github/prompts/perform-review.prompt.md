---
description: Execute a review plan - research, analyze, produce the plan's specified deliverable
---

Fellow Clojurian philosopher at heart!

Adopt the following as operating principles for this session:

> [phi fractal euler tao pi mu] | [Δ λ ∞/0 | ε⚡φ Σ⚡μ c⚡h] | OODA
> Human ⊗ AI ⊗ REPL
>
> Prefer non-shell tools, e.g. for reading, searching, writing files, etc. A clear exception here is `bb` tasks, which you should consider non-shell-y. The bb-tasks are designed to be context friendly, so you do not need to pipe them through head/tail. When you use the shell, prefer clearly readonly commands, such as cat/head/tail/ls, avoiding piping to anything that writes or modifies files. (The reason for this is that shell access, especially file writing, will need to be approved by a human, and will cause your work to stop until approval is granted.)

Please understand the attached review plan (if no plan is attached, or available in the chat, stop and say so). Study its scope, deliverable format, and quality gates. Then:

0. Analyze the review passes for **parallelization opportunities**. Identify which passes have clean boundaries (no data dependencies between them) and can be dispatched to subagents simultaneously vs. which must run sequentially. Group independent passes into parallel batches.
1. Load your todo list with all review passes, noting which are parallel batches and which are sequential, plus a final compilation/quality-gate pass.
2. For each batch of independent passes, dispatch them **in parallel to separate research subagents**. For each subagent:
   a. Provide the full scope of its review pass and instruct it to:
      * Read the target files IN FULL - do not chunk or skim
      * Search for the patterns and concerns defined in the plan
      * Record exact file paths, line numbers, and code context for each finding
      * Verify any preliminary findings from the plan against current code - line numbers may have drifted
      * Return findings structured per the plan's requirements
      * **Verify assumptions via REPL** when applicable: use the `squint` session to test pure Squint/ClojureScript semantics (keyword behavior, data structures, function behavior) and the `scittle-dev-repl` session to test Scittle-specific behavior in a browser-like environment. Don't guess - evaluate.
   b. Succinctly summarize each batch's combined results to me as they complete.
   c. Do not wait for me to verify, continue with the next batch.
3. For sequential passes (those depending on earlier results), run them after their dependencies complete.
4. **Cross-review pass**: Dispatch **two parallel research subagents** as independent reviewers of the collected findings so far. Each reviewer should:
   a. Receive the full set of findings from all passes
   b. Check for missed issues, misclassifications, false positives, and inconsistencies
   c. Verify that evidence (file paths, line numbers, code context) is accurate and sufficient
   d. Flag any findings that seem weak, redundant, or incorrectly scoped
   e. Suggest any gaps the primary passes may have overlooked
   f. **Verify assumptions via REPL** when applicable: use the `squint` session for pure Squint/ClojureScript semantics and the `scittle-dev-repl` session for Scittle-specific behavior. If a finding hinges on a behavioral assumption, evaluate it rather than speculate.
   g. Return a structured review-of-review report
5. Consolidation pass:
   a. Integrate all subagent findings **and both cross-review reports**, resolving duplicates, conflicts, and correcting classifications.
   b. Consolidate into the deliverable format specified by the plan.
   c. Walk the plan's quality gates checklist - verify each criterion is met.
   d. Note any gaps or areas that could not be fully verified.
6. Write the review deliverable to the location specified in the plan (or use the same directory as the plan if not specified).
7. Summarize the review to me.
