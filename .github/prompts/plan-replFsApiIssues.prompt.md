## Plan: Fix REPL FS Confirmation Issues

Source issues document: [dev/docs/repl-fs-api-issues.md](dev/docs/repl-fs-api-issues.md)

Add E2E coverage for each user-visible bug, then make the smallest source changes in the REPL FS API, background confirmation store, and popup UI so confirmations stay consistent (cancel on changes), errors reject promises, badges reflect reality, and confirmation cards can reveal/inspect the affected scripts. This keeps behavior stable and regression-proof before release.

For each issue, the E2E test should assert the user-visible behavior that proves the change is implemented (not just that some code path runs). Implementation work is not considered done until the focused E2E test is green and the full `bb test:e2e` suite passes.

### Steps
1. Implement “cancel confirmation on content changes” in the background confirmation store in [src/background.cljs](src/background.cljs) and storage integration in [src/storage.cljs](src/storage.cljs) using a single canonical cancellation path. Add E2E assertions that a pending confirmation disappears when the underlying script changes.
2. Add confirmation UX improvements: per-item interactions (highlight, reveal, inspect) and bulk actions when more than one confirmation is pending ("Confirm all" and "Cancel all"). Implement in [src/background.cljs](src/background.cljs) (bulk handlers), [src/popup.cljs](src/popup.cljs) (buttons + wiring), and add E2E coverage that proves each UI affordance works.
3. When updating E2E files, normalize deeply nested `describe` forms first (extract anonymous functions) so the test reads like a top-level recipe, then re-run tests to confirm no behavior change before editing test logic.
4. Run `bb test:e2e` and ensure all shards pass.

### Further Considerations
1. Cancellation policy: keep cancellation keyed by script name for now.
2. Inspection semantics: inspect should load the current script state (not the queued snapshot). This relies on confirmation invalidation working correctly when a script changes.
3. Keep recently resolved behaviors stable: badge count updates and Promise rejection semantics are treated as stable and should not regress.
