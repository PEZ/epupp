# REPL File System Sync

## What is REPL FS Sync?

REPL FS Sync lets you manage Epupp userscripts from your editor using REPL commands. Think of it as a bridge between your local files (where you have version control, your favorite editor, and your development workflow) and Epupp's installed scripts (which run in your browser tabs).

Instead of copy-pasting code through the DevTools panel, you can:
- Push scripts from your editor to Epupp: `(epupp.fs/save! code)`
- Pull scripts from Epupp to your editor: `(epupp.fs/show "script.cljs")`
- Manage scripts programmatically: rename, delete, list

## Why Does This Exist?

**Epupp is for live tampering** - you connect your editor's REPL to a web page and interactively modify it while you're using it. This is powerful for experimentation, but eventually you want to:

1. **Save your work** - That clever tamper you just wrote? Save it as a userscript so it runs automatically next time.
2. **Edit installed scripts** - You have a userscript installed, but want to tweak it in your editor with all your tools.
3. **Use version control** - Git, not browser storage. Your userscripts should live in your dotfiles repo.
4. **Avoid complex sync infrastructure** - No DAV servers, no filesystem mounting, no background sync demons. Just REPL primitives you compose yourself.

The REPL channel already exists (you're using it to tamper). The FS API extends it with file-like operations.

## How It Works

### The Mental Model

```
Local Editor           REPL Channel          Epupp (Browser)
─────────────          ────────────          ───────────────
my_script.cljs   -->   (save! code)    -->   Installed script
                                              (runs on matching pages)

                  <--   (show "name")    <--
```

You control when to push and pull. There's no automatic sync - you decide.

### Basic Operations

```clojure
;; List all scripts
(epupp.fs/ls)
;; => [{:fs/name "github_tweaks.cljs" :fs/enabled true :fs/match ["https://github.com/*"]} ...]

;; Get script code
(epupp.fs/show "github_tweaks.cljs")
;; => "{:epupp/script-name \"github_tweaks\" ...}\n(ns github-tweaks)\n..."

;; Save code to Epupp (creates or updates script)
(epupp.fs/save! code-string)
;; => {:fs/success true :fs/name "github_tweaks.cljs"}

;; Rename a script
(epupp.fs/mv! "old_name.cljs" "new_name.cljs")

;; Delete a script
(epupp.fs/rm! "script.cljs")
```

### The Confirmation Workflow

When you call `save!`, `mv!`, or `rm!` without `:fs/force? true`, Epupp asks for confirmation in the popup UI:

```clojure
(epupp.fs/save! new-code)
;; => {:fs/success true
;;     :fs/pending-confirmation true
;;     :fs/name "my_script.cljs"}
```

The promise resolves immediately. The operation is queued. You see a **Pending Confirmations** section in the Epupp popup showing what's about to happen. Click Confirm or Cancel.

**Why confirmations?** You're running a REPL server in your browser tabs - that's powerful and slightly dangerous. The confirmation gives you a quick safety check. You just sent the file, so you know what it is. A quick glance and click confirms it's what you intended.

**Skip confirmations:**
```clojure
(epupp.fs/save! code {:fs/force? true})
;; No confirmation - happens immediately
```

This is useful for automated scripts or when you're confident.

## Typical Workflows

### Workflow 1: Save a Tamper as a Userscript

You've been experimenting in the REPL, tampering with GitHub's UI. Now you want it to run every time:

```clojure
;; Your tamper code (with manifest at top)
(def my-code
  "{:epupp/script-name \"github-tweaks\"
    :epupp/site-match \"https://github.com/*\"}

   (ns github-tweaks)
   (js/console.log \"GitHub enhanced!\")")

;; Save to Epupp
(epupp.fs/save! my-code)

;; Confirm in popup UI
;; Next time you visit GitHub, script runs automatically
```

### Workflow 2: Edit an Installed Script

You have a script installed, but want to edit it in VS Code:

```clojure
;; Pull script code
(epupp.fs/show "github_tweaks.cljs")
;; Copy output to local file

;; Edit in your editor...

;; Push changes back
(epupp.fs/save! (slurp "~/epupp-scripts/github_tweaks.cljs"))
```

### Workflow 3: Sync a Directory (Future)

With editor tooling, you could automate this to feel like a mounted directory. That's not built yet, but the primitives are here.

## Promises and Async

All FS operations return promises. Use `promesa` for clean async code:

```clojure
(require '[promesa.core :as p])

(p/let [scripts (epupp.fs/ls)
        code (epupp.fs/show (-> scripts first :fs/name))]
  (println "First script:" code))
```

## FAQ

### Why not just mount Epupp's script directory as a filesystem?

Browser extensions can't expose native filesystems. WebDAV or sync servers add complexity and dependencies. The REPL channel already exists for tampering - extending it with FS primitives is simple and composable.

### What's the difference between "Pending Confirmations" and script "Allow/Deny"?

Good catch - these are currently separate UI patterns:
- **Pending Confirmations** (yellow section): For FS operations (save, rename, delete). You're modifying Epupp's stored scripts.
- **Allow/Deny** (inline with scripts): For execution permissions. Should this script auto-run on matching pages?

We're working on unifying these patterns, but the concepts are slightly different.

### Can I disable confirmations entirely?

Currently, use `:fs/force? true` on each operation. A future option might add a global "grant filesystem access" mode for editing sessions where all operations happen immediately without confirmation.

### What if I rename a script? Does it lose its enabled state or URL match?

No. Scripts have internal IDs (timestamp-based). When you rename, the ID stays the same, so enabled state, URL match, and other metadata persist. Only the name changes.

### Can I version control my scripts?

Yes! That's the point. Store scripts in `~/.epupp/scripts/` or your dotfiles repo. Use Git. Push changes to Epupp via `save!` when you want them installed.

### What's the script name format?

Script names are normalized to `snake_case.cljs`:
- `my-script` → `my_script.cljs`
- `GitHub Tweaks` → `git_hub_tweaks.cljs`

You can use paths: `github/tweaks.cljs`, `youtube/ad_blocker.cljs`

### What happens if I save! without a manifest?

The `save!` operation requires `:epupp/script-name` in the manifest (the map at the top of your code). Without it, the operation fails with an error.

### How do I see what's in a script without pulling the whole file?

Use `ls` to see metadata:
```clojure
(epupp.fs/ls)
;; Shows name, enabled state, URL match, description, modified timestamp
```

For selective fields, filter the result with `select-keys` or use `cljs.pprint/print-table` for formatted output.

## See Also

- [User Guide - REPL Connection](user-guide.md#repl-connection) - How to connect your editor
- [Examples](examples.md) - Code samples and patterns
- API Reference (coming soon) - Detailed function signatures

---

**Status:** This feature is under active development. The confirmation workflow is stable. A global "grant access" mode may be added in the future.
