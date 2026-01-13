---
description: 'Effective Squint usages'
applyTo: '**'
---

# Squint ClojureScript Dialect - AI Agent Instructions

This project uses [Squint](https://github.com/squint-cljs/squint), a light-weight ClojureScript dialect that compiles to modern JavaScript ES modules.

## Critical Differences from ClojureScript

* `name` function is not implemented.
* Keywords ARE Strings

  This has two important implications:

  1. You don't need `name` to convert keywords to strings:
     ```clojure
     (str "status-" (:type status))  ; Works perfectly
     ```

  2. Keywords and strings compare equal:
     ```clojure
     (= :loaded "loaded")  ; => true
     (= :foo/bar "foo/bar")  ; => true
     ```
     This means you can safely compare a keyword against a string returned from JS interop.
* Data Structures Are Mutable JavaScript Objects

  Squint uses native JavaScript data structures:
  - Maps → JavaScript objects `{}`
  - Vectors → JavaScript arrays `[]`
  - Sets → JavaScript `Set`

  This means:
  - `assoc`, `conj`, etc. mutate in place (unlike ClojureScript)
  - Be aware when passing data structures around

* No Persistent Data Structures. There's no structural sharing or immutability guarantees. If you need immutability, explicitly clone data.

* Async/Await Support

  Squint supports JavaScript async/await with `^:async` metadata and `js-await`:

  ```clojure
  ;; Mark functions as async with ^:async metadata
  (defn ^:async fetch-data []
    (let [response (js-await (js/fetch "/api/data"))
          json (js-await (.json response))]
      json))

  ;; Chain multiple awaits
  (defn ^:async process []
    (let [a (js-await (js/Promise.resolve 10))
          b (js-await (js/Promise.resolve 20))]
      (+ a b)))  ; => 30

  ;; Anonymous async functions need ^:async on fn
  ((^:async fn []
     (let [x (js-await (js/Promise.resolve "hello"))]
       (str x " world"))))  ; => "hello world"
  ```

  **Key points:**
  - `^:async` goes on `defn` or on the `fn` symbol for anonymous functions
  - `js-await` unwraps promises, similar to JavaScript's `await`
  - Async functions always return a Promise
  - Can use `try`/`finally` for cleanup in async code

## Finding Squint Documentation

### Primary Sources

1. **GitHub Repository**: https://github.com/squint-cljs/squint
2. **README**: Comprehensive overview of features and differences
3. **Core Functions**: Check `src/squint/core.js` in the Squint repo for available functions

### Checking Function Availability

If unsure whether a Clojure core function exists in Squint:

1. **Search the Squint repo** for the function name in `src/squint/core.js`
2. **Check exports** - Functions must be explicitly exported to be available
3. **Test with Node.js** - Quick way to check if functions exist:

```bash
node -e "import('squint-cljs/core.js').then(sc => { \
  console.log('name:', typeof sc.name); \
  console.log('str:', typeof sc.str); \
  console.log('assoc:', typeof sc.assoc); \
})"
# Output:
# name: undefined    ← doesn't exist!
# str: function      ← exists
# assoc: function    ← exists
```

### Key Files in Squint Repo

- `src/squint/core.js` - Core runtime functions
- `src/squint/string.js` - String manipulation functions
- `doc/` - Additional documentation

## Common Gotchas

### 1. Unqualified Function Calls

When Squint doesn't recognize a symbol as a core function, it emits an unqualified call:

```clojure
(name :foo)  ; Compiles to: name("foo") - NOT squint_core.name("foo")
```

This causes runtime errors when `name` isn't defined globally.

### 2. Refer Doesn't Help for Missing Functions

```clojure
;; This does NOT make `name` available if it's not in squint-cljs/core.js
(:require [squint.core :refer [name]])
```

The require will silently succeed, but the function still won't exist at runtime.

### 3. Namespace Keywords

Namespace-qualified keywords work but become strings with the slash:

```clojure
:foo/bar  ; Becomes "foo/bar" in JavaScript
```

### 4. Auto-Resolved Keywords Don't Work

`::keyword` syntax is not supported. Use fully-qualified keywords instead:

```clojure
::my-key              ; ❌ Compiler error
:my-namespace/my-key  ; ✅ Works
```

### 5. Bare Namespace Requires Don't Work

Always use vector form with `:as` alias:

```clojure
(:require event-handler)                     ; ❌ Compiler error
(:require [event-handler :as event-handler]) ; ✅ Works
```

### 6. Sets Are Not Callable

In ClojureScript, sets can be used as functions for membership testing. In Squint, sets are JavaScript `Set` objects and cannot be called as functions:

```clojure
(def valid-values #{"a" "b" "c"})

(valid-values "a")           ; ❌ Runtime error: valid_values is not a function
(contains? valid-values "a") ; ✅ Works - returns true
```

### 7. Scittle clj->js Strips Namespace Prefixes

**Critical for Squint ↔ Scittle interop**: When Scittle's `clj->js` converts maps with namespaced keywords, it strips the namespace prefix:

```clojure
;; In Scittle (SCI)
(clj->js {:epupp/require ["scittle://reagent.js"]})
;; => #js {:require #js ["scittle://reagent.js"]}
;;         ^^^^^^^^ namespace stripped!

;; So when reading in Squint background code:
(aget manifest "epupp/require")  ; ❌ nil - key doesn't exist
(aget manifest "require")        ; ✅ finds the value
```

This affects any code where Scittle sends data to Squint extension code via messages. Always check `(js/Object.keys obj)` to see actual keys.

Always use `contains?` for set membership checks.

### 7. Hyphenated Property Access

When accessing JavaScript object properties with hyphenated names, `(.-hyphenated-key obj)` gets converted to `obj.hyphenated_key` (underscore). This breaks when the actual key has a hyphen, like when reading from `chrome.storage.local` where keys are strings like `"test-events"`.

**Solution:** Use `aget` for bracket notation access that preserves the exact key string.

```clojure
;; ❌ Broken - Squint converts hyphen to underscore
(.get js/chrome.storage.local #js ["test-events"]
      (fn [result]
        (.-test-events result)))  ; Becomes result.test_events - wrong!

;; ✅ Works - bracket notation preserves exact key
(.get js/chrome.storage.local #js ["test-events"]
      (fn [result]
        (aget result "test-events")))  ; Becomes result["test-events"] - correct!
```

### 8. Strings Are Sequential

Use `vector?` when you need to distinguish strings from actual collections. In Squint, `sequential?` returns `true` for strings because JavaScript strings are iterable. This differs from ClojureScript where `sequential?` is `false` for strings.

## Debugging Squint Issues

1. **Check compiled `.mjs` output** - Look at the generated JavaScript to understand what's happening
2. **Search for unqualified calls** - If you see `someFn(...)` instead of `squint_core.someFn(...)`, the function may not exist
3. **Browser DevTools** - Runtime errors will show which function is undefined

## Project-Specific Notes

In this project:
- Source files: `src/*.cljs`
- Compiled output: `extension/*.mjs`
- Bundled output: `build/*.js`

Never edit `.mjs` files directly - they're generated by Squint.

## Testing Code in Squint nREPL

Use the Squint nREPL to test pure functions before editing files.

### Verifying the REPL is Available

1. Use `clojure_list_sessions` to check available REPL sessions
2. Look for a session with `"sessionKey": "cljs"` - this is the Squint nREPL
3. Test connectivity by evaluating a simple expression: `(+ 1 2)`

If no Squint session is available, **ask the human to start it**:

> "I need the Squint nREPL to test this code. Please run `bb squint-nrepl` and connect Calva to it (port 1339)."

Do NOT start the server yourself - the human manages their terminal sessions.

### Testing Pure Functions

Once connected, test interactively:

```clojure
;; Test pure functions from url_matching.cljs
(require '[url-matching :refer [url-matches-pattern?]])
(url-matches-pattern? "https://github.com/foo" "*://github.com/*")
;; => true

;; Test storage helpers
(require '[storage :refer [generate-script-id]])
(generate-script-id "My Cool Script")
;; => "my-cool-script"
```

**What works in Squint nREPL:**
- Pure functions (data transformations, URL matching, ID generation)
- Core Squint functions (`map`, `filter`, `assoc`, etc.)
- String manipulation, regex operations

**What doesn't work:**
- Browser APIs (`chrome.*`, `document.*`, `window.*`)
- Extension-specific code (message passing, storage)
- Anything requiring the DOM

**Workflow:** Develop and test pure logic in the nREPL first, then integrate into browser-specific code with confidence.
