# DevTools Panel E2E Testing Research

**Date**: 2026-01-05  
**Status**: Research phase - Implementation blocked by environment issues  
**Context**: Enhancing `e2e/panel_test.cljs` with functional tests

## Background

The current `panel_test.cljs` only verifies that the panel HTML file loads. It doesn't test any of the actual panel functionality. This document outlines research and strategy for comprehensive panel testing with Playwright.

## Challenge: Testing Chrome DevTools Panels

DevTools panels are a special context in Chrome extensions that:
1. Run in a separate frame/context from regular pages
2. Are created by `chrome.devtools.panels.create()` API
3. Have access to `chrome.devtools.inspectedWindow` for page interaction
4. Cannot be directly accessed via `chrome-extension://` URLs (require DevTools to be open)

## Current Panel Architecture (from src/panel.cljs)

```clojure
;; In src/devtools.cljs - creates the panel
(js/chrome.devtools.panels.create
 "Scittle Tamper"
 "icons/icon-32.png"
 "panel.html"
 (fn [_panel] ...))

;; In src/panel.cljs - panel implementation
;; - Code editor (textarea)
;; - Eval button (evaluates code in inspected page)
;; - Save script form (name, URL pattern)
;; - Results area (shows input/output/errors)
;; - Scittle status management
```

## Playwright DevTools Testing Approaches

### Approach 1: Direct Panel HTML Access (Current - LIMITED)

**What it tests**:
```clojure
(test "panel HTML file loads successfully"
  ;; Navigate to chrome-extension://<id>/panel.html
  ;; Verify basic DOM structure
  )
```

**Limitations**:
- Panel loads but NOT in DevTools context
- No `chrome.devtools.inspectedWindow` API available
- Cannot test actual panel functionality
- Cannot test interaction with inspected page

**Useful for**:
- ✅ Verifying HTML structure
- ✅ Verifying CSS loads
- ✅ Verifying scripts are included
- ❌ Testing panel functionality
- ❌ Testing code evaluation
- ❌ Testing inspected page interaction

### Approach 2: CDP (Chrome DevTools Protocol) Integration

Playwright supports CDP for advanced Chrome automation:

```javascript
// Pseudo-code example
const cdp = await page.context().newCDPSession(page);

// Navigate to a test page
await page.goto('http://localhost:8765/test.html');

// Open DevTools programmatically (possible via CDP)
// Access DevTools panel context
// Interact with panel UI
```

**Research needed**:
1. Can Playwright open DevTools on a page via CDP?
2. Can it access the DevTools panels context?
3. Can it interact with custom panel UI?
4. Can it verify inspectedWindow evaluations?

**References to explore**:
- [Playwright CDP Session API](https://playwright.dev/docs/api/class-cdpsession)
- [Chrome DevTools Protocol](https://chromedevtools.github.io/devtools-protocol/)
- CDP `Target.createTarget` for DevTools window
- CDP `Target.attachToTarget` for panel context

### Approach 3: Hybrid Testing Strategy (RECOMMENDED)

Combine multiple testing levels:

#### Level 1: Unit Tests (Vitest) - DONE
Test pure panel logic:
```clojure
;; test/panel_actions_test.cljs
(deftest eval-action-test
  (testing "eval action sets evaluating state"
    (let [result (handle-action 
                   {:panel/code "(+ 1 2)" :panel/scittle-status :loaded}
                   {}
                   [:editor/ax.eval])]
      (is (true? (get-in result [:uf/db :panel/evaluating?])))
      (is (= [[:editor/fx.eval-in-page "(+ 1 2)"]] 
             (:uf/fxs result))))))
```

✅ Already have tests in `test/panel_test.cljs`

#### Level 2: Panel UI in Isolation (Playwright)
Test panel UI without DevTools context:
```clojure
(test "panel code editor accepts input"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Type code into editor
            (js-await (.fill page "textarea" "(+ 1 2 3)"))
            ;; Verify value
            (js-await (-> (expect (.locator page "textarea")) 
                         (.toHaveValue "(+ 1 2 3)")))))))))
```

**Can test**:
- ✅ UI rendering and layout
- ✅ Form inputs work
- ✅ Button clicks trigger actions
- ✅ State persistence (via localStorage)
- ⚠️ Mock evaluation (won't have real inspectedWindow)

#### Level 3: Integration Test via Automation
Test full workflow programmatically:
```clojure
(test "panel evaluates code in inspected page"
  ;; 1. Start browser-nrepl server
  ;; 2. Load extension with test page
  ;; 3. Programmatically: Open DevTools panel
  ;; 4. Send eval command to panel via messaging
  ;; 5. Verify result appears in test page
  )
```

**Challenges**:
- Opening DevTools programmatically is non-standard
- May need CDP or browser flags
- May need to simulate DevTools lifecycle

#### Level 4: Manual Testing Checklist
For features that are hard to automate:
```markdown
- [ ] Open DevTools on test page
- [ ] Navigate to Scittle Tamper panel
- [ ] Type code in editor
- [ ] Press Ctrl+Enter
- [ ] Verify result appears
- [ ] Save as script
- [ ] Verify script appears in popup
```

## Proposed Test Implementation Plan

### Phase 1: Enhanced UI Tests (CAN IMPLEMENT NOW)

Enhance current `panel_test.cljs` with:

```clojure
(test "panel UI renders all components"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            
            ;; Verify code editor
            (js-await (-> (expect (.locator page "textarea")) (.toBeVisible)))
            
            ;; Verify eval button
            (js-await (-> (expect (.locator page ".btn-eval")) (.toBeVisible)))
            
            ;; Verify clear button
            (js-await (-> (expect (.locator page ".btn-clear")) (.toBeVisible)))
            
            ;; Verify save script form
            (js-await (-> (expect (.locator page "#script-name")) (.toBeVisible)))
            (js-await (-> (expect (.locator page "#script-match")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeVisible)))
            
            ;; Verify results area
            (js-await (-> (expect (.locator page ".results-area")) (.toBeVisible)))))))))

(test "code editor accepts and displays input"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))
                test-code "(def x 42)"]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.fill page "textarea" test-code))
            (js-await (-> (expect (.locator page "textarea")) 
                         (.toHaveValue test-code)))))))))

(test "save script form fields work"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            
            ;; Fill script name
            (js-await (.fill page "#script-name" "Test Script"))
            (js-await (-> (expect (.locator page "#script-name")) 
                         (.toHaveValue "Test Script")))
            
            ;; Fill URL pattern
            (js-await (.fill page "#script-match" "https://example.com/*"))
            (js-await (-> (expect (.locator page "#script-match")) 
                         (.toHaveValue "https://example.com/*")))))))))

(test "eval button is disabled when code is empty"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Button should be disabled initially
            (js-await (-> (expect (.locator page ".btn-eval")) 
                         (.toBeDisabled)))))))))

(test "save button is disabled when required fields are empty"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Save button should be disabled initially
            (js-await (-> (expect (.locator page ".btn-save")) 
                         (.toBeDisabled)))))))))
```

### Phase 2: CDP-Based DevTools Tests (RESEARCH REQUIRED)

Research and implement:
1. Opening DevTools via CDP
2. Accessing panel context
3. Simulating inspectedWindow APIs
4. End-to-end eval flow

### Phase 3: Integration Tests (ALTERNATIVE)

If CDP approach doesn't work:
1. Test panel via message passing
2. Simulate DevTools lifecycle with mocks
3. Focus on observable behavior (storage, messages)

## Current Test Coverage

| Component | Unit Tests | UI Tests | Integration |
|-----------|------------|----------|-------------|
| panel-actions | ✅ 11 tests | - | - |
| Panel UI render | - | ✅ Basic | ❌ |
| Code editor | - | ⚠️ Needs enhancement | ❌ |
| Save form | - | ⚠️ Needs enhancement | ❌ |
| Eval flow | ✅ Actions | ❌ | ❌ |
| Storage | - | ⚠️ Could test | ❌ |
| DevTools context | - | ❌ Hard | ❌ Hard |

## Limitations and Trade-offs

### What We CAN Test Well
- ✅ Panel UI rendering and structure
- ✅ Form inputs and validation
- ✅ Button states (enabled/disabled)
- ✅ Pure action handlers (unit tests)
- ✅ Storage interactions (mockable)
- ✅ CSS and styling

### What's HARD to Test
- ⚠️ Code evaluation in inspected page (needs real DevTools context)
- ⚠️ chrome.devtools.inspectedWindow API (needs DevTools)
- ⚠️ Scittle injection flow (complex async chain)
- ⚠️ Panel lifecycle in DevTools (not standard automation)

### Recommended Balance
1. **Comprehensive UI tests** (90% coverage of UI interactions)
2. **Solid unit tests** (100% coverage of pure logic)
3. **Manual test checklist** for DevTools-specific features
4. **Integration tests via REPL** (already have in `repl_test.clj`)

## References

1. [Playwright Chrome Extensions](https://playwright.dev/docs/chrome-extensions)
2. [Chrome DevTools Protocol](https://chromedevtools.github.io/devtools-protocol/)
3. [chrome.devtools.panels API](https://developer.chrome.com/docs/extensions/reference/devtools_panels/)
4. [Playwright CDP Sessions](https://playwright.dev/docs/api/class-cdpsession)
5. Existing tests:
   - `e2e/popup_test.cljs` - Good patterns for UI testing
   - `e2e/repl_ui_spec.cljs` - Integration test patterns
   - `test/panel_actions_test.cljs` - Unit test patterns

## Next Steps (When Environment Works)

1. ✅ Implement Phase 1 enhanced UI tests
2. 🔬 Research CDP approach for Phase 2
3. 📝 Create manual test checklist
4. ✅ Ensure good coverage via unit + UI + integration tests
5. 📋 Document limitations and trade-offs

## Conclusion

We can significantly improve panel testing with **enhanced UI tests** using the existing Playwright framework. While true DevTools context testing is challenging, we can achieve good coverage through:
- **Unit tests** for logic (already done)
- **Enhanced UI tests** for components (ready to implement)
- **Integration tests** via REPL (already exists)
- **Manual checklist** for edge cases

This pragmatic approach provides strong confidence while acknowledging the limitations of automated testing for Chrome DevTools panels.
