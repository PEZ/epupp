## Plan: Fix REPL FS Confirmation Issues

Source issues document: [dev/docs/repl-fs-api-issues.md](dev/docs/repl-fs-api-issues.md)

Add E2E coverage for each user-visible bug, then make the smallest source changes in the REPL FS API, background confirmation store, and popup UI so confirmations stay consistent (cancel on changes), errors reject promises, badges reflect reality, and confirmation cards can reveal/inspect the affected scripts. This keeps behavior stable and regression-proof before release.

For each issue, the E2E test should assert the user-visible behavior that proves the change is implemented (not just that some code path runs). Implementation work is not considered done until the focused E2E test is green and the full `bb test:e2e` suite passes.

### Steps
1. Lock in regressions with new E2E tests in [e2e/fs_ui_reactivity_test.cljs](e2e/fs_ui_reactivity_test.cljs) and [e2e/fs_write_test.cljs](e2e/fs_write_test.cljs) for: cancel-on-change, badge count, promise rejection. Each test must include assertions that would fail if the feature is not implemented.
2. Implement “cancel confirmation on content changes” in the background confirmation store in [src/background.cljs](src/background.cljs) and storage integration in [src/storage.cljs](src/storage.cljs) using a single canonical cancellation path.
3. Fix Promise semantics in the page API surface in [src/userscripts/epupp_fs.cljs](src/userscripts/epupp_fs.cljs) so failed FS operations reject (and tests assert `.catch` runs).
4. Make the toolbar badge reflect total pending items: match approvals + FS confirmations. Implement in [src/icons.cljs](src/icons.cljs) and add an E2E assertion that enqueues both kinds and expects the summed count.
5. Add confirmation UX improvements: per-item interactions (highlight, reveal, inspect) and bulk actions when more than one confirmation is pending ("Confirm all" and "Cancel all"). Implement in [src/background.cljs](src/background.cljs) (bulk handlers), [src/popup.cljs](src/popup.cljs) (buttons + wiring), and add E2E coverage for bulk confirm/cancel.
6. Run `bb test:e2e` and ensure all shards pass.

### Further Considerations
1. Badge semantics: the toolbar badge should sum pending match approvals + pending FS confirmations (for example, 2 approvals + 3 FS confirmations = 5).
2. Cancellation policy: keep cancellation keyed by script name for now.
3. Inspection semantics: inspect should load the current script state (not the queued snapshot). This relies on confirmation invalidation working correctly when a script changes.
