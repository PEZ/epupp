# Multi-Server E2E Test Infrastructure

**Status:** Implemented

Two browser-nrepl servers now start automatically for E2E tests, enabling realistic multi-tab testing with different ports.

## Port Allocation

| Server | nREPL Port | WebSocket Port | Purpose |
|--------|------------|----------------|---------|
| Primary | 12345 | 12346 | Main test server |
| Secondary | 12347 | 12348 | Multi-tab scenarios |

## Files Modified

1. `scripts/tasks.clj` - `with-browser-nrepls` starts both servers
2. `e2e/fixtures.cljs` - Exports `ws-port-1`, `ws-port-2`, `nrepl-port-1`, `nrepl-port-2`
3. `e2e/repl_ui_spec.cljs` - Uses port constants from fixtures
4. `e2e/popup_test.cljs` - Uses `ws-port-1` from fixtures
5. `e2e/log_powered_test.cljs` - Uses `ws-port-1` from fixtures
6. `dev/docs/testing-e2e.md` - Documents both servers

## Usage in Tests

```clojure
;; Import from fixtures
[fixtures :refer [ws-port-1 ws-port-2 nrepl-port-1 nrepl-port-2]]

;; Connect tab to server 1
(connect-tab popup tab-id ws-port-1)

;; Connect different tab to server 2
(connect-tab popup other-tab-id ws-port-2)
```

## Cost

- Startup: ~500ms additional (parallel process spawn)
- Memory: ~20MB additional (Babashka process)
- Benefit: Enables multi-tab REPL testing with independent servers
