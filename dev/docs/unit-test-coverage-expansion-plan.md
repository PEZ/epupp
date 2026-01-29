# Unit Test Coverage Expansion Plan

Expand unit test coverage for pure business logic that was identified during the E2E → Unit test coverage audit. Focus on extracting testable logic from E2E tests into focused unit tests for faster TDD cycles and better regression protection.

## Baseline

**Current coverage:** 399 unit tests in 15 files
**Final coverage:** 454 unit tests (+55 tests, +14% coverage)

**Expected outcome:** ~20-30 new unit tests covering pure functions identified in E2E tests

**Goal:** Extract pure business logic from E2E scenarios into fast, focused unit tests

---

## Standard

- All edits delegated to **Clojure-editor subagent**
- Before each batch: delegate to **epupp-testrunner subagent** for baseline
- After each batch: delegate to **epupp-testrunner subagent** for verification
- REPL experiments (`squint` session) to verify logic before editing
- Tick checkboxes without inserting commentary blocks
- Work bottom-to-top through checklist

---

## Required Reading

### Testing Documentation
- [dev/docs/testing.md](testing.md) - Testing philosophy and workflow
- [dev/docs/testing-unit.md](testing-unit.md) - Unit test patterns, Vitest usage, Squint gotchas

### Source Files to Test
- [src/storage.cljs](../../src/storage.cljs) - Built-in script sync logic
- [src/script_utils.cljs](../../src/script_utils.cljs) - Script diffing and filtering
- [src/panel_actions.cljs](../../src/panel_actions.cljs) - Save button state logic
- [src/popup_actions.cljs](../../src/popup_actions.cljs) - Modified scripts tracking, shadow sync
- [src/popup_utils.cljs](../../src/popup_utils.cljs) - Origin validation (if exists)

### Test Files to Extend
- [test/storage_test.cljs](../../test/storage_test.cljs) - Add built-in sync tests
- [test/script_utils_test.cljs](../../test/script_utils_test.cljs) - Add diff and filter tests
- [test/panel_test.cljs](../../test/panel_test.cljs) - Add save button state tests
- [test/popup_actions_test.cljs](../../test/popup_actions_test.cljs) - Add shadow sync tests (may need creating)

---

## Checklist

### Phase 1: Quick Wins (High Value, Low Effort)

#### 1.1 Built-in script update detection
Location: [src/storage.cljs](../../src/storage.cljs#L330) - `builtin-update-needed?`

Test pure comparison logic for determining when a built-in script needs updating from bundled version.

- [x] addressed in code
- [x] verified by tests

**Test cases:**
- Identical scripts → no update needed
- Code differs → update needed
- Name differs → update needed
- Match patterns differ → update needed
- Description differs → update needed
- Run-at differs → update needed
- Inject differs → update needed
- Nil existing (new built-in) → update needed

**File:** test/storage_test.cljs

#### 1.2 Script list diffing
Location: [src/script_utils.cljs](../../src/script_utils.cljs#L235) - `diff-scripts`

Test diffing logic for detecting added, removed, and modified scripts (used for flash animations).

- [x] addressed in code
- [x] verified by tests

**Test cases:**
- Added scripts detected
- Removed scripts detected
- Modified scripts detected (code changed)
- No changes → empty diff
- Multiple simultaneous changes
- Code change vs metadata-only change

**File:** test/script_utils_test.cljs

#### 1.3 Script visibility filtering
Location: [src/script_utils.cljs](../../src/script_utils.cljs#L159) - `filter-visible-scripts`

Test filtering logic for hiding built-in scripts in ls output.

- [x] addressed in code
- [x] verified by tests

**Test cases:**
- include-hidden? true → returns all scripts
- include-hidden? false → filters out built-ins
- Empty list → returns empty
- Only built-ins with hidden=false → returns empty
- Mixed scripts with hidden=false → returns only user scripts

**File:** test/script_utils_test.cljs

#### 1.4 Save operation conflict detection
Location: [src/panel_actions.cljs](../../src/panel_actions.cljs) - save button state logic

Extract and test the logic that determines when a script name conflict would occur during save.

- [x] addressed in code
- [x] verified by tests

**Test cases:**
- New script with unique name → no conflict
- New script with existing name → conflict
- Rename to unique name → no conflict
- Rename to existing name → conflict
- Rename to same name (no-op) → no conflict
- Case sensitivity handling

**File:** test/panel_test.cljs or new test/panel_utils_test.cljs if logic is extracted

---

### Phase 2: Medium Effort

#### 2.1 Built-in script building from manifest
Location: [src/storage.cljs](../../src/storage.cljs#L312) - `build-bundled-script`

Test building script maps from bundled metadata and code.

- [x] addressed in code
- [x] verified by tests

**Test cases:**
- Complete manifest with all fields
- Minimal manifest (only script-name)
- Manifest with string inject
- Manifest with array inject
- Manifest with match patterns
- Manifest without match (manual-only)
- Invalid run-at → defaults to document-idle

**File:** test/storage_test.cljs

#### 2.2 Script→base-info response shape
Location: [src/repl_fs_actions.cljs](../../src/repl_fs_actions.cljs) - `script->base-info` or similar

Test FS API response structure for script metadata.

- [x] addressed in code
- [x] verified by tests

**Test cases:**
- Required fields present
- Optional fields omitted when nil
- Built-in flag correctly mapped
- Date fields formatted correctly
- Match patterns as vector

**File:** test/repl_fs_actions_test.cljs (may need creating)

#### 2.3 Save/Create/Rename button state logic
Location: [src/panel_actions.cljs](../../src/panel_actions.cljs) or panel view logic

Extract pure function that determines button label and enabled state.

- [x] addressed in code
- [x] verified by tests

**Test cases:**
- New script → "Create" button, enabled when name valid
- Editing script, name unchanged → "Save" button
- Editing script, name changed → "Rename" button
- Invalid name → button disabled
- Reserved namespace → button disabled
- Empty name → button disabled

**File:** test/panel_test.cljs

#### 2.4 Modified scripts tracking action
Location: [src/popup_actions.cljs](../../src/popup_actions.cljs#L96) - `:popup/ax.mark-scripts-modified`

Test tracking of recently modified scripts with deferred cleanup.

- [x] addressed in code
- [x] verified by tests

**Test cases:**
- Single script marked modified
- Multiple scripts marked in batch
- Appends to existing modified set
- Clear action removes all

**File:** test/popup_actions_test.cljs (may need creating)

#### 2.5 Shadow list sync logic
Location: [src/popup_actions.cljs](../../src/popup_actions.cljs#L106) - shadow sync actions

Test shadow list animation state management (entering/leaving flags).

- [x] addressed in code
- [x] verified by tests

**Test cases:**
- Added items get entering flag
- Removed items get leaving flag
- Updated items preserve content without animation
- Defer actions scheduled correctly
- Clear entering after delay
- Remove leaving after delay

**File:** test/popup_actions_test.cljs

---

### Phase 3: Architecture-Constrained

#### 3.1 Origin URL validation
Location: [src/popup_utils.cljs](../../src/popup_utils.cljs) or similar - `valid-origin?`

Test validation for trailing slash, scheme requirements.

- [x] addressed in code
- [x] verified by tests

**Test cases:**
- Valid http:// with trailing slash
- Valid https:// with trailing slash
- Valid with port and trailing colon
- Invalid: missing scheme
- Invalid: wrong scheme (ftp://)
- Invalid: no trailing delimiter

**File:** test/popup_utils_test.cljs (may need creating)

#### 3.2 Auto-connect decision logic (DEFERRED)
Location: [src/popup.cljs](../../src/popup.cljs) or background - connection decision logic

**Note:** May require refactoring to extract pure function first.

- [ ] addressed in code
- [ ] verified by tests

**Test cases (after extraction):**
- Setting enabled + disconnected → should connect
- Setting disabled → should not connect
- Already connected → should not reconnect
- Invalid port → should not connect

**File:** test/popup_utils_test.cljs or test/connection_utils_test.cljs (after refactoring)

#### 3.3 Auto-reconnect decision logic (DEFERRED)
Location: [src/ws_bridge.cljs](../../src/ws_bridge.cljs) or background

**Note:** May require refactoring to extract pure function first.

- [ ] addressed in code
- [ ] verified by tests

**Test cases (after extraction):**
- Setting enabled + connection lost → should reconnect
- Setting disabled → should not reconnect
- Clean disconnect → should not reconnect
- Network error → should reconnect (if enabled)

**File:** test/connection_utils_test.cljs (after refactoring)

#### 3.4 Gist installer URL detection (DEFERRED)
Location: [src/userscripts/epupp/gist_installer.cljs](../../src/userscripts/epupp/gist_installer.cljs) - URL parsing

Extract and test Gist URL parsing/detection logic.

- [ ] addressed in code
- [ ] verified by tests

**Test cases:**
- Valid gist.github.com URL
- Valid github.com gist URL
- Invalid: not a gist URL
- Extract gist ID from URL
- Handle URL variations (trailing slash, query params)

**File:** test/gist_installer_test.cljs (may need creating)

#### 3.5 Confirmation dialog decision logic (DEFERRED)
Location: Various - delete confirmations, clear confirmations

**Note:** May be too UI-coupled to extract cleanly. Assess during implementation.

- [ ] addressed in code
- [ ] verified by tests

**Test cases (if extractable):**
- Delete script with match → requires confirmation
- Delete manual-only script → no confirmation needed
- Clear all → always requires confirmation
- Bulk delete → requires confirmation

**File:** TBD based on where logic lives

---

## Batch Execution Order

**Batch A: Quick Wins - Storage and Script Utils (1.1-1.3)**
1. Run testrunner baseline
2. Implement tests for `builtin-update-needed?` in storage_test.cljs
3. Implement tests for `diff-scripts` in script_utils_test.cljs
4. Implement tests for `filter-visible-scripts` in script_utils_test.cljs
5. Run testrunner verification

**Batch B: Quick Wins - Conflict Detection (1.4)**
1. Run testrunner baseline
2. Extract conflict detection to pure function (if needed)
3. Implement tests in panel_test.cljs or new panel_utils_test.cljs
4. Run testrunner verification

**Batch C: Medium Effort - Storage and FS API (2.1-2.2)**
1. Run testrunner baseline
2. Implement tests for `build-bundled-script` in storage_test.cljs
3. Create repl_fs_actions_test.cljs and implement `script->base-info` tests
4. Run testrunner verification

**Batch D: Medium Effort - Panel and Popup Actions (2.3-2.5)**
1. Run testrunner baseline
2. Extract button state logic (if needed) and implement tests in panel_test.cljs
3. Create popup_actions_test.cljs
4. Implement tests for modified tracking and shadow sync
5. Run testrunner verification

**Batch E: Validation and Decision Logic (3.1-3.5)**
1. Assess which items are practical to test without major refactoring
2. For each practical item:
   - Extract pure function if needed
   - Implement tests
   - Run testrunner verification
3. Document any items deferred due to architecture constraints

---

## Results

**Execution date:** 2026-01-29

**Final metrics:**
- Starting tests: 399
- Tests added: 55
- Final tests: 454
- Coverage increase: +14%

**Phase 1 (Quick Wins):** 4/4 completed - 27 tests added
**Phase 2 (Medium Effort):** 5/5 completed - 28 tests added
**Phase 3 (Architecture-Constrained):** 1/5 completed, 4 deferred

**Deferred items:**
- 3.2 Auto-connect: Logic deeply embedded in callback-based chrome.storage API
- 3.3 Auto-reconnect: Distributed across message protocol components
- 3.4 Gist installer: Feature not yet implemented
- 3.5 Confirmations: Trivial inline logic not worth extracting

**Key refactorings:**
- `builtin-update-needed?` made public for testability
- `build-bundled-script` made public for testability
- `detect-name-conflict` extracted from panel.cljs to script_utils.cljs

**Learnings:**
- Uniflow pattern makes pure state transformations trivially testable
- Callback-based Chrome APIs resist unit testing - keep E2E coverage for those
- Pure function extraction from UI code is high-value refactoring

---

## Follow-up Work: Uniflow "Gather-then-Decide" Pattern

The deferred items 3.2 (auto-connect) and 3.3 (auto-reconnect) can be made unit testable by refactoring to the "gather-then-decide" Uniflow pattern. This moves decision logic from effects up into actions.

### Current Implementation

Decision logic is interleaved with async Chrome API calls, making unit testing impossible without mocking:

```clojure
;; In handle-navigation! - decisions mixed with async gathering
(let [enabled? (js-await (get-auto-connect-settings))
      auto-reconnect? (js-await (get-auto-reconnect-setting))
      ...]
  (cond
    enabled? (js-await (connect-tab! ...))
    (and auto-reconnect? in-history?) (js-await (connect-tab! ...))))
```

### Target: Gather-then-Decide with `:uf/prev-result`

Uniflow now supports `:uf/prev-result` in `:uf/dxs`, enabling a clean separation:

```clojure
;; TRIGGERING ACTION: Declares the recipe - gather then decide
:nav/ax.check-connection
(let [[tab-id] args]
  {:uf/fxs [[:uf/await :nav/fx.gather-context tab-id]]
   :uf/dxs [[:nav/ax.decide-connection :uf/prev-result]]})

;; EFFECT: Dumb data gatherer - just returns context map
:nav/fx.gather-context
(let [[tab-id] args
      settings (js-await (get-auto-connect-settings))
      auto-reconnect? (js-await (get-auto-reconnect-setting))
      history @!history]
  {:tab-id tab-id
   :auto-connect-all? (:enabled? settings)
   :auto-reconnect? auto-reconnect?
   :in-history? (bg-utils/tab-in-history? history tab-id)
   :history-port (bg-utils/get-history-port history tab-id)
   :ws-port (:ws-port settings)})

;; ACTION: Pure decision logic - 100% unit testable
:nav/ax.decide-connection
(let [[context] args
      {:keys [tab-id auto-connect-all? auto-reconnect? in-history? history-port ws-port]} context]
  (cond
    auto-connect-all?
    {:uf/fxs [[:ws/fx.connect tab-id ws-port]]}

    (and auto-reconnect? in-history? history-port)
    {:uf/fxs [[:ws/fx.connect tab-id history-port]]}

    :else nil))

;; EFFECT: Dumb executor - just connects
:ws/fx.connect
(let [[tab-id port] args]
  (js-await (connect-tab! tab-id port)))
```

The framework executes effects first, then threads the result into deferred actions via `:uf/prev-result`.

### Benefits Over Current Implementation

| Current | Target |
|---------|--------|
| Decision logic interleaved with async calls | Decision logic in pure action |
| Requires Chrome API mocking to test | Unit testable without mocking |
| Single monolithic function | Clear separation: gather → decide → execute |
| Hard to reason about all branches | Each decision maps to a test case |

Additional benefits:
- **Recipe-style readability** - Triggering action declares "gather context, then decide"
- **No dispatch in effects** - Framework handles threading via `:uf/prev-result`
- **Follows Uniflow philosophy** - "actions decide, effects execute"

### Items Enabled

| Deferred Item | Refactoring Needed |
|---------------|-------------------|
| 3.2 Auto-connect | Extract to `:nav/ax.decide-connection` |
| 3.3 Auto-reconnect | Same action handles both cases |

### Test Cases (Post-Refactoring)

```clojure
(testing "auto-connect-all supersedes everything"
  (let [result (handle-action {} {} [:nav/ax.decide-connection
                                      {:auto-connect-all? true :ws-port "1340" :tab-id 1}])]
    (expect (.-fxs result) :toEqual [[:ws/fx.connect 1 "1340"]])))

(testing "auto-reconnect only when in history with port"
  (let [result (handle-action {} {} [:nav/ax.decide-connection
                                      {:auto-reconnect? true :in-history? true
                                       :history-port "1341" :tab-id 1}])]
    (expect (.-fxs result) :toEqual [[:ws/fx.connect 1 "1341"]])))

(testing "no connection when disabled"
  (let [result (handle-action {} {} [:nav/ax.decide-connection
                                      {:auto-connect-all? false :auto-reconnect? false :tab-id 1}])]
    (expect result :toBeNull)))
```

---

## Success Criteria

- All Phase 1 items have tests
- At least 3 out of 5 Phase 2 items have tests
- Phase 3 items assessed and practical ones have tests
- Zero new test failures
- Zero new lint warnings
- Total unit test count increases by ~20-30 tests

---

## Original Plan-producing Prompt

Create a dev/docs plan document for implementing unit tests to cover gaps identified in the e2e → unit test coverage audit.

**Context:**

An audit of all 30 e2e test files identified logic that could be unit tested but isn't. The gaps fall into three phases:

**Phase 1 - Quick Wins (High Value, Low Effort)**
1. `builtin-update-needed?` tests in storage_test.cljs - pure comparison logic for built-in script sync
2. `diff-scripts` tests in script_utils_test.cljs - diffing old/new script lists (used for flash animations)
3. `filter-visible-scripts` tests in script_utils_test.cljs - filtering by builtin status
4. Conflict detection logic tests in panel_test.cljs - name collision detection for save operations

**Phase 2 - Medium Effort**
1. `script->base-info` response shape tests - verifying FS API response structure
2. Button state decision logic in panel - determining Save/Create/Rename button states
3. Mark scripts modified action in popup_actions - tracking recently modified scripts
4. Shadow sync animation logic in popup_actions - enter/leave state management
5. `build-bundled-script` tests in storage_test.cljs - building script maps from bundled metadata

**Phase 3 - Architecture-Constrained**
1. Origin URL validation - trailing slash, scheme validation
2. Auto-connect/auto-reconnect decision logic (requires refactoring to extract pure functions)
3. Gist installer URL parsing/detection
4. Confirmation dialog decision logic

**Document Format Requirements:**

Follow the exact format of existing plans in dev/docs (uniflow-compliance-fix-plan.md, smooth-animations-plan.md):

1. Title and summary explaining the goal
2. Standard section - test workflow rules, delegation patterns
3. Required Reading section listing:
   - Relevant dev docs (testing.md, testing-unit.md)
   - Source files that will be modified
   - Existing test files to extend
4. Checklist section with phases and items, each item having:
   - Brief description of what to test
   - Location (source file and function)
   - Dual checkboxes: `- [ ] addressed in code` and `- [ ] verified by tests`
5. Batch Execution Order describing how to group the work
6. Original Plan-producing Prompt section at the end

**Key Details:**

- Baseline before starting: 399 unit tests in 15 files
- Expected outcome: ~20-30 new unit tests covering pure business logic
- What NOT to include:
  - Tests requiring browser context
  - Tests that would duplicate existing coverage
  - E2E test retirement (keep as separate future work)

Create: /Users/pez/Projects/browser-jack-in/dev/docs/unit-test-coverage-expansion-plan.md
