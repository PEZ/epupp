# Unit Testing

Detailed documentation for unit testing with Vitest. For overview, see [testing.md](testing.md).

## Commands

```bash
bb test        # Single run
bb test:watch  # Watch mode (Squint + Vitest in parallel)
```

## File Locations

| Purpose | Path |
|---------|------|
| Source tests | `test/*.cljs` |
| Compiled output | `build/test/**/*_test.mjs` |

## What to Unit Test

- **Uniflow action handlers** - Pure state transitions
- **URL pattern matching** - `url-matches-pattern?` and helpers
- **Script utilities** - ID generation, validation, normalization
- **Manifest parsing** - `:epupp/*` annotation extraction
- **Data transformations** - Any pure function

## Writing Tests

Tests use Vitest with Squint. Example pattern:

```clojure
(ns my-module-test
  (:require ["vitest" :refer [describe it expect]]
            [my-module :as m]))

(describe "my-function"
  (it "handles basic case"
    (-> (expect (m/my-function "input"))
        (.toBe "expected")))

  (it "handles edge case"
    (-> (expect (m/my-function nil))
        (.toBeUndefined))))
```

## Gotchas (Squint/JS Interop)

### nil vs undefined

Clojure `nil` becomes JS `undefined` in Squint. Use `.toBeUndefined()` instead of `.toBeNull()`:

```clojure
;; ✅ Correct
(-> (expect (get-optional-value)) (.toBeUndefined))

;; ❌ Will fail - nil is undefined, not null
(-> (expect (get-optional-value)) (.toBeNull))
```

### Keywords are Strings

In Squint, keywords are strings. This affects equality:

```clojure
(= :foo "foo")  ;; => true in Squint
```

### Sets are Not Callable

Use `contains?` for membership, not the set as a function:

```clojure
;; ✅ Correct
(contains? #{:a :b} :a)

;; ❌ Runtime error in Squint
(#{:a :b} :a)
```

## Test Organization

Group related tests in `describe` blocks:

```clojure
(describe "url-matching"
  (describe "url-matches-pattern?"
    (it "matches exact URLs" ...)
    (it "matches wildcards" ...))

  (describe "extract-hostname"
    (it "extracts from full URL" ...)
    (it "handles edge cases" ...)))
```

## Running Specific Tests

Vitest supports filtering:

```bash
# Run tests matching pattern
npx vitest run --grep "url-matching"

# Run specific file
npx vitest run build/test/url_matching_test.mjs
```

## Test-Driven Development

TDD is encouraged for all new functionality, changes, regression, and basically all work.

- Unit tests run in ~1s, enabling rapid iteration
- Pure functions are easy to test in isolation
- Squint REPL lets you explore before writing tests
- Tests document expected behavior for future maintainers
