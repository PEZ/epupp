# E2E Test Structure Migration Plan

## Objective

Migrate all E2E test files from deeply nested `test` forms within `describe` callbacks to a flat structure that is easier to edit with structural editing tools.

## Problem

Current E2E test files (except [fs_ui_reactivity_test.cljs](../../e2e/fs_ui_reactivity_test.cljs)) have deeply nested structures like this:

```clojure
(test "Suite name"
  (^:async fn []
    (.describe test "Nested describe"
      (fn []
        (.beforeAll test (fn [] ...))
        (test "Test 1" (^:async fn [] ...))
        (test "Test 2" (^:async fn [] ...))))))
```

This creates major editing problems for AI agents using structural editing tools because:
- Forms are deeply nested within multiple callback functions
- Line numbers shift significantly during edits
- Bracket balancing becomes error-prone
- Tool failure rate increases dramatically

## Solution: Flat Test Function Pattern

The model file [e2e/fs_ui_reactivity_test.cljs](../../e2e/fs_ui_reactivity_test.cljs) demonstrates the desired pattern:

### Key Characteristics

1. **Private test functions** at top level (using `defn-`)
2. **Single shallow describe block** at the end
3. **Test registration** via simple function references
4. **Shared setup** via atoms and setup functions

### Example Structure

```clojure
(ns e2e.example-test
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [...]]))

;; Shared state (if needed)
(def ^:private !context (atom nil))
(def ^:private !ext-id (atom nil))

;; Helper functions
(defn ^:async setup-browser! []
  ;; Setup logic
  )

;; Test functions as private defns
(defn- ^:async test_feature_one []
  ;; Test implementation
  )

(defn- ^:async test_feature_two []
  ;; Test implementation
  )

;; Single shallow describe block - ALWAYS at the end
(.describe test "Suite Name"
  (fn []
    (.beforeAll test (fn [] (setup-browser!)))
    (.afterAll test (fn [] (when @!context (.close @!context))))

    (test "Feature one works" test_feature_one)
    (test "Feature two works" test_feature_two)))
```

### Benefits

- Top-level forms are easy to locate by line number
- No deep nesting makes structural editing reliable
- Each test function is independently editable
- Shared setup/teardown logic is clear and separate
- Agents can use replace_top_level_form without precision issues

## Migration Process (Strict Requirements)

### Before Starting ANY File

1. **Run full E2E test suite**: `bb test:e2e`
2. **Verify all tests pass**
3. **Commit current state** with message: "Pre-migration checkpoint before [filename]"

### Per-File Migration Steps

For each file in the "Files to Migrate" list below:

1. **Mark file as in-progress** in this plan (add `[WIP]` prefix)
2. **Read the model file** [e2e/fs_ui_reactivity_test.cljs](../../e2e/fs_ui_reactivity_test.cljs) to refresh understanding
3. **Use structural editing tools only** - NO shell commands like `sed`, `awk`, `perl`, etc.
4. **Only `bb` commands allowed** for running tests and builds
5. **Extract test bodies** from nested `test` calls into private `defn-` functions
   - Use meaningful function names: `test_feature_description` (underscores, not hyphens)
   - Keep the `^:async` metadata on the `fn` when extracting
6. **Move shared setup** (atoms, setup functions) to top of file
7. **Create single describe block** at the END of the file
8. **Preserve test order** from original file
9. **Run full E2E suite**: `bb test:e2e`
10. **Verify no new failures**
11. **Commit immediately** with message: "Migrate [filename] to flat test structure"
12. **Mark file as complete** in this plan (add `[✓]` prefix)

### If Tests Fail

- **DO NOT proceed to next file**
- Fix the current file until tests pass
- If stuck, ask for human guidance
- Only commit when tests are green

## Files to Migrate

Migration order: Work through files in any order, but complete one fully before starting the next.

Status legend:
- `[ ]` Not started
- `[WIP]` Work in progress
- `[✓]` Complete and committed
- `[SKIP]` Already follows pattern

- `[SKIP]` [e2e/fs_ui_reactivity_test.cljs](../../e2e/fs_ui_reactivity_test.cljs) - MODEL FILE
- `[✓]` [e2e/extension_test.cljs](../../e2e/extension_test.cljs)
- `[✓]` [e2e/fs_read_test.cljs](../../e2e/fs_read_test.cljs)
- `[ ]` [e2e/fs_write_test.cljs](../../e2e/fs_write_test.cljs) - DEFERRED (complex, save for last)
- `[✓]` [e2e/integration_test.cljs](../../e2e/integration_test.cljs)
- `[ ]` [e2e/panel_eval_test.cljs](../../e2e/panel_eval_test.cljs)
- `[ ]` [e2e/panel_save_test.cljs](../../e2e/panel_save_test.cljs)
- `[ ]` [e2e/panel_state_test.cljs](../../e2e/panel_state_test.cljs)
- `[ ]` [e2e/popup_autoconnect_test.cljs](../../e2e/popup_autoconnect_test.cljs)
- `[ ]` [e2e/popup_connection_test.cljs](../../e2e/popup_connection_test.cljs)
- `[ ]` [e2e/popup_core_test.cljs](../../e2e/popup_core_test.cljs)
- `[ ]` [e2e/popup_icon_test.cljs](../../e2e/popup_icon_test.cljs)
- `[ ]` [e2e/require_test.cljs](../../e2e/require_test.cljs)
- `[ ]` [e2e/userscript_test.cljs](../../e2e/userscript_test.cljs)

**Note**: [e2e/repl_ui_spec.cljs](../../e2e/repl_ui_spec.cljs) is not included as it uses a different pattern (no nested describes).

## Quality Gates

After completing ALL migrations:

1. Full E2E test suite passes: `bb test:e2e`
2. No lint errors: check problem report
3. All files follow the model pattern
4. Commit history shows one commit per file migration
5. This plan document updated with all `[✓]` markers

## Critical Constraints

### Absolutely Forbidden

- **NO shell text-processing commands**: No `sed`, `awk`, `perl`, `ruby -e`, etc.
- **NO batch/bulk operations**: Migrate ONE file at a time, fully
- **NO proceeding on red tests**: Fix failures immediately

### Required Tools

- **ONLY structural editing tools**: `replace_top_level_form`, `insert_top_level_form`, `clojure_append_code`
- **ONLY `bb` commands**: `bb test:e2e`, `bb squint-compile`

### Process Discipline

- Full test run BEFORE starting a file
- Full test run AFTER completing a file
- Immediate commit after successful migration
- One file to completion before starting next

## Success Criteria

Migration is complete when:

- All files in the list marked `[✓]`
- All E2E tests pass
- No structural editing difficulties remain
- Pattern is consistent across all test files
