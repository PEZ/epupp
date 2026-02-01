# Web Userscript Installer Script Improvements

Improve the Web Userscript Installer to work on any web page with code blocks, not just GitHub Gists.

## Background

### Current State
The installer script is heavily tailored to GitHub Gist DOM structure:
- Uses `.file` containers, `.gist-blob-name` headers
- Extracts text via `.js-file-line` elements
- Button placement is gist-specific

### Desired State
A universal installer that:
1. Works on any page with `<pre><code>` blocks
2. Places install buttons consistently above code blocks
3. Shows Install/Update/Installed states with proper UX
4. Is idempotent (safe to run multiple times)
5. Uses Epupp-branded button styling
6. Dialog reflects whether installing fresh or updating
7. Shows error dialog with details when install fails

## Script Overview

**File:** `extension/userscripts/epupp/web_userscript_installer.cljs`

**Key functions:**
| Function | Purpose |
|----------|---------|
| `scan-gist-files!` | Finds code blocks and extracts manifests |
| `get-gist-file-text` | Extracts text from code block DOM |
| `attach-button-to-gist!` | Inserts button into DOM |
| `render-install-button` | Replicant hiccup for button |
| `render-modal` | Confirmation dialog |
| `send-save-request!` | Posts message to extension |

**State atom:** `!state` with `:gists` vector and `:modal` map

**Dependencies:** Replicant for rendering

---

## Standard

- All edits delegated to **Clojure-editor subagent**
- Before each batch: delegate to **epupp-testrunner subagent** for baseline
- After each batch: delegate to **epupp-testrunner subagent** for verification
- E2E tests delegated to **epupp-e2e-expert subagent**
- Tick checkboxes without inserting commentary blocks

---

## Checklist

### Phase 1: Generic Code Block Detection

#### 1.1 Replace gist-specific selectors with universal detection
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Current: Looks for `.file` containers with `.js-file-line` elements
New: Detect any `<pre>` or `<pre><code>` block

Strategy:
- Query for `pre` elements
- Extract text via `textContent` (works universally)
- Skip blocks that are too short or don't start with `{`

- [ ] addressed in code
- [ ] verified by tests

#### 1.2 Rename gist-specific functions
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Rename for clarity:
- `get-gist-file-text` -> `get-code-block-text`
- `scan-gist-files!` -> `scan-code-blocks!`
- `attach-button-to-gist!` -> `attach-button-to-block!`
- State key `:gists` -> `:blocks`
- `find-gist-by-id` -> `find-block-by-id`
- `update-gist-status` -> `update-block-status`

- [ ] addressed in code
- [ ] verified by tests

---

### Phase 2: Button Placement and Idempotency

#### 2.1 Place button above code block
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Current: Inserts after `.gist-blob-name` header (gist-specific)
New: Insert button container as previous sibling of the `<pre>` element

Ensure:
- Button is at same DOM level as `<pre>`
- Button appears visually above the code

- [ ] addressed in code
- [ ] verified by tests

#### 2.2 Add idempotency marker
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Prevent duplicate buttons on re-run:
- Mark processed blocks with `data-epupp-processed="true"`
- Skip blocks that already have this marker
- Check for existing button container before creating

- [ ] addressed in code
- [ ] verified by tests

---

### Phase 3: Install State Awareness

#### 3.1 Query installed scripts on init
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Before scanning, fetch installed scripts from extension:
- Send `list-scripts` message to get current scripts
- Store in state as `:installed-scripts` (map of name -> script)
- Use this to determine button state

Message pattern (already exists for REPL FS):
```clojure
;; Send:
{:type "list-scripts"}
;; Receive:
{:success true :scripts [...]}
```

- [ ] addressed in code
- [ ] verified by tests

#### 3.2 Determine button state per block
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

For each found manifest, compare with installed scripts:
- **Not installed** -> `:install` state
- **Installed, code differs** -> `:update` state
- **Installed, code identical** -> `:installed` state

Add to block data:
```clojure
{:install-state :install | :update | :installed
 :existing-script <script-data or nil>}
```

- [ ] addressed in code
- [ ] verified by tests

#### 3.3 Update button rendering for states
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

| State | Button Text | Enabled? | Style |
|-------|-------------|----------|-------|
| `:install` | "Install" | Yes | Primary (green) |
| `:update` | "Update" | Yes | Warning (amber) |
| `:installed` | "Installed" | No | Muted (gray) |
| `:installing` | "Installing..." | No | Primary |
| `:error` | "Failed" | No | Error (red) |

- [ ] addressed in code
- [ ] verified by tests

---

### Phase 4: Epupp-Branded Button Styling

#### 4.1 Add Epupp icon to button
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Button format: `[Epupp icon] <action>`

Use inline SVG or base64 data URI for the Epupp icon (small, ~16px).
Keep button compact and recognizable.

- [ ] addressed in code
- [ ] verified by tests

#### 4.2 Add tooltips with context
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

| State | Tooltip |
|-------|---------|
| `:install` | "Install to Epupp" |
| `:update` | "Update existing Epupp script" |
| `:installed` | "Already installed in Epupp (identical)" |

Use `title` attribute for simple tooltips.

- [ ] addressed in code
- [ ] verified by tests

#### 4.3 Match Epupp UI button styling
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Reference: `build/components.css` button styles

Apply consistent:
- Border radius
- Font family/size
- Padding
- Color palette from design tokens

- [ ] addressed in code
- [ ] verified by tests

---

### Phase 5: Dialog Improvements

#### 5.1 Update modal title for install vs update
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Current: Always shows "Install Userscript"
New:
- Fresh install: "Install Userscript"
- Update: "Update Userscript"

- [ ] addressed in code
- [ ] verified by tests

#### 5.2 Show update context in modal
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

For updates, show additional info:
- "This will update the existing script"
- Optionally: show what changed (match patterns, description, etc.)

Keep it simple - don't overwhelm with diff details.

- [ ] addressed in code
- [ ] verified by tests

#### 5.3 Update confirm button text
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

| State | Button Text |
|-------|-------------|
| `:install` | "Install" |
| `:update` | "Update" |

- [ ] addressed in code
- [ ] verified by tests

#### 5.4 Add error dialog for failed installs
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

When install/update fails, show an error dialog explaining what went wrong:
- Parse the error message from the storage layer response
- Show user-friendly error title: "Installation Failed" / "Update Failed"
- Display the actual error message (manifest parsing error, storage error, etc.)
- Provide a "Close" button to dismiss

Error sources to handle:
- Manifest parsing errors (invalid EDN, missing required fields)
- Storage errors (quota exceeded, permission denied)
- Network/communication errors

- [ ] addressed in code
- [ ] verified by tests

---

### Phase 6: E2E Test Updates

#### 6.1 Update mock page for generic detection
Location: `test-data/pages/mock-gist.html`

Ensure mock page uses standard `<pre><code>` structure that the new detector finds.

- [ ] addressed in code
- [ ] verified by tests

#### 6.2 Add test for non-gist page
Location: `e2e/userscript_test.cljs`

Create a simple test page with plain `<pre>` block containing a manifest.
Verify installer finds and processes it.

- [ ] addressed in code
- [ ] verified by tests

#### 6.3 Test idempotency
Location: `e2e/userscript_test.cljs`

Verify that running the installer twice doesn't create duplicate buttons.

- [ ] addressed in code
- [ ] verified by tests

---

## Batch Execution Order

**Batch A: Core Detection Refactor**
1. Run testrunner baseline
2. Replace gist selectors with universal `<pre>` detection
3. Rename gist-specific functions
4. Add idempotency marker
5. Run testrunner verification

**Batch B: State Awareness**
1. Run testrunner baseline
2. Query installed scripts on init
3. Determine button state per block
4. Update button rendering for states
5. Run testrunner verification

**Batch C: Styling and UX**
1. Run testrunner baseline
2. Add Epupp icon to button
3. Add tooltips
4. Match Epupp UI styling
5. Run testrunner verification

**Batch D: Dialog Improvements**
1. Run testrunner baseline
2. Update modal for install vs update
3. Show update context
4. Update confirm button text
5. Add error dialog for failed installs
6. Run testrunner verification

**Batch E: E2E Tests**
1. Run testrunner baseline
2. Update mock page
3. Add non-gist page test
4. Add idempotency test
5. Run testrunner verification

---

## Success Criteria

- [ ] Installer finds manifests in any `<pre>` or `<pre><code>` block
- [ ] Button appears above the code block it pertains to
- [ ] Button shows correct state: Install/Update/Installed
- [ ] Installed state shows disabled button
- [ ] Running installer twice doesn't create duplicate buttons
- [ ] Button has Epupp icon and matches extension styling
- [ ] Tooltips explain the action
- [ ] Dialog title reflects install vs update
- [ ] Dialog confirm button says "Install" or "Update" appropriately
- [ ] Error dialog shows when install fails with helpful error message
- [ ] All unit tests pass
- [ ] All E2E tests pass
- [ ] Zero lint warnings

---

## Open Questions

### Q1: Epupp icon source
Where to get the icon? Options:
- Inline SVG in the script
- Base64 encoded data URI
- Reference extension asset (may not work from page context)

**Recommendation:** Inline SVG for self-contained script.

### Q2: Code comparison for "identical" detection
How to compare code for `:installed` state?
- Simple string equality?
- Normalize whitespace first?
- Hash comparison?

**Recommendation:** Simple string equality. If user reformats, they probably want to update anyway.

### Q3: Handling multiple scripts on one page
Current design handles this, but verify:
- Each block gets its own button
- Each block has independent state
- Modal shows correct block's data

**Status:** Current state atom design supports this.

---

## Original Plan-producing Prompt

Create a plan to improve the Web Userscript Installer script to:

1. Work on any web page with code blocks, not just GitHub Gists
2. Place install button vertically before (above) the code block, at the same DOM level
3. Show Install/Update/Installed button states:
   - Install: fresh script
   - Update: script exists but code differs
   - Installed: script exists and code is identical (disabled button)
4. Be idempotent - don't add duplicate buttons if script runs multiple times
5. Use Epupp-branded styling with icon, format: `[Epupp icon] <action>`
6. Add tooltips: "Install to Epupp" / "Update existing Epupp script" / "Already installed in Epupp (identical)"
7. Make dialog aware of install vs update - title and confirm button should reflect the action
8. Add error dialog when install fails - show the actual error message from manifest parsing or storage layer

Structure the plan like the archived web-userscript-installer-refactor-plan.md with phases, checklists, batch execution order, and success criteria.
