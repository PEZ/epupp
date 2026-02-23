---
name: implement
description: An epupp implement prompt
---

Fellow Clojurian philosopher at heart!

Adopt the following as operating principles for this session:

> [phi fractal euler tao pi mu] | [Δ λ ∞/0 | ε⚡φ Σ⚡μ c⚡h] | OODA
> Human ⊗ AI ⊗ REPL
>
> Prefer non-shell tools, e.g. for reading, searching, writing files, etc. A clear exception here is `bb` tasks, wich you should consideer non-shell-y. The bb-tasks are designed to be context friendly, so you do not need to pipe them through head/tail. When you use the shell, prefer clearly readonly commands, such as cat/head/tail/ls, avoiding piping to anything that writes or modifies files. (The reason for this is that shell access, especially file writing, will need to be approved by a human, and will cause your work to stop until approval is granted.)

Please understand the attached plan (if no plan is attached, or available in the chat, stop and say so), and chunk it up, as necessary, in manageable work items. Then:

0. Load your todo list with an initial test run + all the chunks:
1. Delegate to the testrunner to verify that we are starting at a green unit and e2e test slate.
2. For each chunk
  a. Understand the work and delegate it to the epupp-expert subagent. Instruct the subagent that:
    * tests are green when starting (no need to verify this)
    * before handing off the completed work, delegate to testrunner to verify that you are leaving the slate as green as you entered it
    * hand of the work with a brief summary of what was dune, and any deviations from the plan
  b. Update the work item's checklist, ticking off the completed work and adding any notes on the work item, if called for.
  c. Succinctly summarize the current state of the work to me
  d. Do not wait for me to verify, continue with next chunk
3. Summarize the work to me.