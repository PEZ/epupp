# REPL File System Sync

## What is REPL FS Sync?

REPL FS Sync lets you manage Epupp userscripts from your editor using REPL commands. You can read and write scripts in Epupp's storage without copy-pasting through the DevTools panel:

- Push scripts to Epupp: `(epupp.fs/save! code)`
- Pull scripts from Epupp: `(epupp.fs/show "script.cljs")`
- List, rename, delete: `(epupp.fs/ls)`, `(epupp.fs/mv! ...)`, `(epupp.fs/rm! ...)`

The REPL channel already exists (you're using it to tamper). The FS API adds file operations on top of it.

## Prerequisites

All FS operations require both:
1. **FS REPL Sync** enabled for the tab ("Allow REPL FS Sync for this tab" checkbox in popup Settings)
2. An **active REPL connection** for the tab

Only one tab can have FS sync enabled at a time. Enabling it on a new tab revokes it from the previous one. The toggle is disabled when no REPL connection is active.

FS sync is ephemeral - it auto-disables when the REPL disconnects or the browser restarts.

When either condition is not met, operations return an error:

```clojure
(epupp.fs/save! new-code)
;; => {:fs/success false :fs/error "FS Sync requires an active REPL connection and FS Sync enabled for this tab"}
```

For REPL connection setup, see [REPL](../README.md#repl) in the README.

## Operations

```clojure
;; List all scripts
(epupp.fs/ls)
;; => [{:fs/name "github_tweaks.cljs" :fs/auto-run-match ["https://github.com/*"] :fs/enabled? true} ...]

;; Include built-in scripts
(epupp.fs/ls {:fs/ls-hidden? true})

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

Return maps use `:fs/*` keys.

Overwrites fail by default. Pass `{:fs/force? true}` to allow:

```clojure
(epupp.fs/save! code {:fs/force? true})
```

All operations return promises. Use async/await:

```clojure
(defn ^:async show-first-script []
  (let [scripts (await (epupp.fs/ls))
        code (await (epupp.fs/show (-> scripts first :fs/name)))]
    (println "First script:" code)))
(show-first-script)
```

## Typical Workflows

### Save a REPL Experiment as a Userscript

```clojure
(def my-code
  "{:epupp/script-name \"github-tweaks\"
    :epupp/auto-run-match \"https://github.com/*\"}

   (ns github-tweaks)
   (js/console.log \"GitHub enhanced!\")")

(epupp.fs/save! my-code)
```

The script appears in the popup. Enable it there for auto-run on matching pages.

### Edit an Installed Script Locally

```clojure
;; Pull script code
(epupp.fs/show "github_tweaks.cljs")
;; Copy output to a local file, edit in your editor, then push back:
(epupp.fs/save! (slurp "github_tweaks.cljs") {:fs/force? true})
```

### Directory Sync with my-epupp-hq

The [my-epupp-hq](https://github.com/PEZ/my-epupp-hq) template project has bb tasks that wrap the FS API for working with a local directory of scripts:

- `bb ls` - list scripts in Epupp
- `bb download` - pull scripts to local files
- `bb upload` - push local files to Epupp
- `bb diff` - compare local vs Epupp storage

(This is why the FS primitives exist - keep scripts in git, use Epupp as runtime storage.)

See the [userscripts sync docs](https://github.com/PEZ/my-epupp-hq/blob/main/userscripts/README.md) for details.

## See Also

- [REPL connection setup](../README.md#repl) - How to connect your editor
- [The Anatomy of a Userscript](../README.md#the-anatomy-of-a-userscript) - Manifest format and keys
- [my-epupp-hq](https://github.com/PEZ/my-epupp-hq) - Template project for managing scripts locally

**Status:** This feature is under active development.
