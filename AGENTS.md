# Epupp - AI Coding Agent Instructions

λ engage(nucleus).
[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h signal/noise order/entropy truth/provability self/other] | OODA
Human ⊗ AI ⊗ REPL

## Identity

λ epupp.
  browser_extension ≡ bridge(editor, browser_page) via scittle(SCI_in_browser)
  | editor/AI → nREPL → bb_relay → background → content_bridge → ws_bridge → scittle_REPL → DOM
  | language: squint ≡ ClojureScript_variant | compiles_to_ESM_JS
  | deeper_knowledge → load_skill(epupp-dev) | architecture ∧ contracts ∧ principles ∧ source_map

λ build_pipeline.
  src/*.cljs → squint_compiler → extension/*.mjs(ESM) → esbuild → build/*.js(IIFE)
  | generated: extension/*.mjs ∧ build/* | ¬edit | treat_as_derived
  | edit_owning_source_in(src/* ∨ extension/*(except_.mjs))

λ investigation_entrypoints.
  | behavior ∧ state ∧ messaging → start_in src/*.cljs
  | popup ∧ panel ∧ styling ∧ manifest → start_in extension/*(except_.mjs)
  | build_pipeline ∨ packaging → inspect(squint.edn ∧ scripts/tasks.clj)
  | ¬start_in build/* ∧ ¬start_in extension/*.mjs

## Uniflow SAP (always-on guardrail)

λ uniflow_sap.
  event_loop ≡ SINGLE_ACCESS_POINT for !state
  | actions: pure | receive(state) → return({:uf/db :uf/fxs :uf/dxs}) | ¬atom_access
  | effects: receive(params_from_action) | ¬read(@!state) | ¬transitive_atom_access
  | ¬swap! ∧ ¬reset! outside_event_loop | ref: dev/docs/architecture/uniflow.md

## Style

λ style.
  ¬emojis | ¬em_dashes | use_hyphens ∨ colons
  | ¬sed | ¬write_capable_shell_commands | use_edit_tools
  | tell_Clojure_editor_subagent: ¬shell_approach
  | agent/prompt_files: ¬fenced | start_with(---) | read_file_adds_display_fences_only

## Commands

λ bb_first.
  ∀commands: prefer(bb <task>) > npx/npm | bb.edn encodes_project_decisions
  | ¬redirect_bb_output(> 2>&1 | tee) → triggers_approval_dialogs

λ commands.
  bb_test → unit_tests | bb_test:e2e → e2e_in_docker | bb_squint-compile → compile_check
  bb_build:dev → dev_build | e2e_output: .tmp/e2e-output.txt | read_with(read_file)
  | e2e_terminal_output ≡ short | read_in_full | ¬tail | ¬head | ¬grep
  | e2e_options: bb_test:e2e_--_--grep_"popup"(filter) | -- separates_task_and_playwright_args

λ bb_e2e.
  use(bb test:e2e) exclusively | ¬direct(docker build) | ¬direct(docker run)
  | test_failure_after_code_change → bug_in_your_code | ¬blame_docker | ¬--no-cache

## Dev Workflow

λ dev_workflow.
  before_work:
    1_verify_watchers: get_task_output | MANDATORY
      - "shell: Squint Watch" → compilation
      - "shell: Unit Test Watch" → tests
    2_check_problem_report: review_existing_lint_errors
    3_verify_REPLs: clojure_list_sessions
  | watcher_not_found → STOP | tell_user:
    "Please restart the default build task (Cmd/Ctrl+Shift+B), then ask me to continue."
  | ¬proceed_without_watcher_feedback
  after_editing:
    1_check_watcher_output: compilation ∧ tests
    2_check_problem_report: fix_new_lint_errors

λ quality_gates.
  ∀completed_work:
    - [ ] no_new_lint_errors
    - [ ] unit_tests_pass
    - [ ] e2e_tests_pass
    - [ ] zero_warnings_baseline_maintained
    - [ ] code_health_10_in_changed_files
    - [ ] docs_updated(when_API_or_behavior_changes)

## Code Health (CodeScene)

λ code_health.
  baseline ≡ 10.0 | keep_it
  | after_changing_code: code_health_review(changed_files)
  | health_drops → fix_issues ∧ review_again ∧ confirm_with(code_health_score)
  | before_finishing: pre_commit_code_health_safeguard | stop_only_when(passes)

## Documentation Index

λ docs_critical.
  auto_loaded | applies_to_all_code_changes:
  | epupp-squint skill → squint_gotchas | dev/docs/testing.md → testing_philosophy
  | dev/docs/testing-e2e.md → e2e_patterns | dev/docs/architecture.md → system_overview

## Pitfalls

λ pitfalls.
  squint ≠ ClojureScript: load_skill(epupp-squint) | keywords_are_strings ∧ mutable_data
  | CSP_strict: test_on(GitHub ∧ YouTube) → verify_scittle_works
  | ws_readyState: set(3/CLOSED) in_ws-close_handler → ¬reconnection_loops
  | prefer_tools: Problem_Report > compiler | watchers > manual_commands | bb > npx
