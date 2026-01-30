# Gather-then-Decide Pattern Expansion Plan

Identify and refactor callback-heavy Chrome API code to use Uniflow's gather-then-decide pattern, making decision logic pure and unit-testable.

## Background

The [unit-test-coverage-expansion-plan-completed.md](unit-test-coverage-expansion-plan-completed.md) demonstrated that:

1. **Callback-based Chrome APIs resist unit testing** when decision logic is interleaved with async calls
2. **The gather-then-decide pattern** cleanly separates concerns:
   - Effects gather async data and return it
   - Framework threads result via `:uf/prev-result` in `:uf/dxs`
   - Decision actions are pure and trivially testable
3. **Results were excellent**: Items 3.2 (auto-connect) and 3.3 (auto-reconnect) went from "untestable" to 11 unit tests covering all branches

### The Pattern

```clojure
;; TRIGGERING ACTION: Declares the recipe
:domain/ax.do-something
(let [[id] args]
  {:uf/fxs [[:uf/await :domain/fx.gather-context id]]
   :uf/dxs [[:domain/ax.decide-something :uf/prev-result]]})

;; EFFECT: Dumb data gatherer - calls Chrome APIs, returns context map
:domain/fx.gather-context
(let [[id] args
      data-a (js-await (chrome.storage.local.get ...))
      data-b (js-await (chrome.tabs.get id))]
  {:id id :setting-a (:value data-a) :tab-url (.-url data-b)})

;; ACTION: Pure decision logic - 100% unit testable
:domain/ax.decide-something
(let [[context] args
      {:keys [id setting-a tab-url]} context]
  (cond
    setting-a {:uf/fxs [[:domain/fx.do-thing-a id]]}
    (allowed? tab-url) {:uf/fxs [[:domain/fx.do-thing-b id]]}
    :else nil))
```

## Baseline

**Current coverage:** 469 unit tests, 109 E2E tests
**Pattern applied to:** Navigation decisions (auto-connect, auto-reconnect)

**Goal:** Identify additional callback-heavy code that would benefit from this pattern

---

## Standard

- All edits delegated to **Clojure-editor subagent**
- Before each batch: delegate to **epupp-testrunner subagent** for baseline
- After each batch: delegate to **epupp-testrunner subagent** for verification
- REPL experiments (`squint` session) to verify logic before editing

---

## Required Reading

### Pattern Documentation
- [dev/docs/architecture/uniflow.md](architecture/uniflow.md) - Section "Result Threading to Deferred Actions"
- [dev/docs/unit-test-coverage-expansion-plan-completed.md](unit-test-coverage-expansion-plan-completed.md) - Section "Completed: Uniflow Gather-then-Decide Pattern"

### Reference Implementation
- [src/background_utils.cljs](../../src/background_utils.cljs) - `decide-auto-connection` pure function
- [src/background_actions.cljs](../../src/background_actions.cljs) - `:nav/ax.decide-connection` action
- [src/background.cljs](../../src/background.cljs) - `:nav/ax.handle-navigation` triggering action, gathering effects

---

## Checklist

### Phase 1: Discovery (Identify Candidates)

#### 1.1 Popup connection logic
Location: [src/popup.cljs](../../src/popup.cljs) or [src/popup_actions.cljs](../../src/popup_actions.cljs)

Investigate connection initiation flow from popup. Look for patterns where Chrome API results feed into decisions.

- [ ] investigated
- [ ] candidates identified

**Known patterns to look for:**
- `chrome.tabs.query` → decision about which tab to connect
- `chrome.storage.local.get` → decision based on saved settings
- Port validation → decision about whether to proceed

**File:** TBD after investigation

#### 1.2 Background message handlers
Location: [src/background.cljs](../../src/background.cljs)

Investigate `chrome.runtime.onMessage` handlers. Some may gather data then make decisions inline.

- [ ] investigated
- [ ] candidates identified

**Known patterns to look for:**
- Message handlers that call multiple Chrome APIs before deciding response
- Handlers with `cond` or `if` branches after async gathering

**File:** TBD after investigation

#### 1.3 Storage operations with decisions
Location: [src/storage.cljs](../../src/storage.cljs)

Investigate storage operations that read data then make decisions about what to write.

- [ ] investigated
- [ ] candidates identified

**Known patterns to look for:**
- Read-modify-write patterns with conditional logic
- Migration or reconciliation logic

**File:** TBD after investigation

#### 1.4 Content script injection decisions
Location: [src/bg_inject.cljs](../../src/bg_inject.cljs)

Investigate injection logic. May gather tab state before deciding what to inject.

- [ ] investigated
- [ ] candidates identified

**Known patterns to look for:**
- Tab URL checks before injection
- Permission or origin validation before proceeding

**File:** TBD after investigation

---

### Phase 2: Assessment (Evaluate Candidates)

For each candidate identified in Phase 1, assess:

1. **Complexity** - Is the decision logic complex enough to warrant extraction?
2. **Testability gain** - Would unit tests meaningfully improve coverage?
3. **Refactoring effort** - How invasive is the change?

Mark candidates as:
- **HIGH** - Complex decision logic, high testability gain, moderate effort
- **MEDIUM** - Moderate complexity, some testability gain
- **LOW** - Simple logic, minimal benefit from extraction
- **SKIP** - Trivial or not worth the effort

---

### Phase 3: Implementation

For HIGH and MEDIUM candidates, follow TDD:

1. Write unit tests for the pure decision function first
2. Extract the decision logic to a pure function
3. Create the triggering action with gather-then-decide pattern
4. Wire up the gathering effects
5. Verify all tests pass

---

## Batch Execution Order

**Batch A: Discovery**
1. Investigate popup connection logic (1.1)
2. Investigate background message handlers (1.2)
3. Investigate storage operations (1.3)
4. Investigate content script injection (1.4)
5. Document candidates found

**Batch B: Assessment**
1. Evaluate each candidate
2. Prioritize HIGH items
3. Document assessment rationale

**Batch C: Implementation (per HIGH candidate)**
1. Run testrunner baseline
2. Write failing tests for decision function
3. Extract pure function and action
4. Create triggering action and effects
5. Run testrunner verification

---

## Results

**Execution date:** TBD

**Candidates identified:** TBD
**Candidates implemented:** TBD
**Tests added:** TBD

---

## Success Criteria

- All Phase 1 items investigated
- Candidates assessed with clear rationale
- HIGH priority items have tests
- Zero test failures
- Zero lint warnings
- Pattern documented with examples

---

## Original Plan-producing Prompt

Create a follow-up plan for applying the gather-then-decide Uniflow pattern to additional callback-heavy Chrome API code.

**Context:**

The unit-test-coverage-expansion-plan successfully demonstrated that:
- Items 3.2 (auto-connect) and 3.3 (auto-reconnect) became testable via gather-then-decide
- 11 unit tests now cover all decision branches
- The pattern cleanly separates async gathering from pure decision logic

**Suggested areas to investigate:**
- Popup connection logic
- Background message handlers with inline decisions
- Storage read-modify-write patterns
- Content script injection decisions

**Document requirements:**
- Summarize learnings from the completed plan
- Include the pattern reference
- Use discovery → assessment → implementation phases
- Same checklist structure as previous plan
