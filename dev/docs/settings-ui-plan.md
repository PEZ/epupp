# Settings UI Implementation Plan

**Created:** January 7, 2026
**Status:** Planning

Add a settings panel to the popup with user-editable whitelist for userscript installation origins.

## Overview

Currently, allowed script origins are hardcoded in config. Users should be able to add custom origins (e.g., self-hosted GitLab, Gitea instances) through a settings UI in the popup.

## Design

### UI Flow

1. Cog icon in popup header (top right)
2. Click toggles between main view and settings view
3. Settings view shows the title **Scittle Tamper Settings** and then sections where each section have Title, Description, and content, and for now just one section
   - Title: "Allowed Userscript-install Base URLs"
   - Description: explaining the whitelist
   - Content
      - Read-only display of default origins (from config)
      - Editable list of user-added origins
      - Input field + Add button for new origins
      - Delete button for user-added origins

### Data Model

**Config defaults** (build-time, read-only):
```clojure
;; config/dev.edn and config/prod.edn
:allowedScriptOrigins ["https://github.com/"
                       "https://gist.github.com/"
                       "https://raw.githubusercontent.com/"
                       "https://gitlab.com/"
                       "https://codeberg.org/"
                       "http://localhost:"      ; dev only
                       "http://127.0.0.1:"]     ; dev only
```

**User additions** (runtime, stored in chrome.storage.local):
```clojure
;; Storage key: "userAllowedOrigins"
;; Example: ["https://git.mycompany.com/" "https://gitea.internal/"]
```

**Merged at runtime** in background.cljs:
```clojure
(defn- allowed-script-origins []
  (concat (or (.-allowedScriptOrigins config) [])
          (storage/get-user-allowed-origins)))
```

### URL Validation Change

Current approach checks hostname + protocol separately. New approach uses prefix matching:

```clojure
;; Before (hostname-based)
(and (= protocol "https:")
     (some #(or (= hostname %) (.endsWith hostname (str "." %))) hosts))

;; After (prefix-based) - simpler, more flexible
(defn url-origin-allowed? [url]
  (some #(.startsWith url %) (allowed-script-origins)))
```

This allows entries like:
- `https://github.com/` - any github.com path
- `https://gitlab.mycompany.com/` - specific self-hosted instance
- `http://localhost:` - any localhost port (dev)

## Implementation Checklist

### Phase 1: Config and Storage Changes

- [ ] **Update config format** ([config/dev.edn](../../config/dev.edn), [config/prod.edn](../../config/prod.edn))
  - Rename `allowedScriptHosts` to `allowedScriptOrigins`
  - Change values from hostnames to URL prefixes (include scheme and trailing slash)
  - Add localhost entries to dev.edn only

- [ ] **Add storage functions** ([src/storage.cljs](../../src/storage.cljs))
  - `get-user-allowed-origins` - returns vector from storage
  - `add-user-allowed-origin!` - adds origin, persists
  - `remove-user-allowed-origin!` - removes origin, persists
  - Update `load!` to include `userAllowedOrigins` key
  - Update `!db` schema to include `:storage/user-allowed-origins`

- [ ] **Update validation** ([src/background.cljs](../../src/background.cljs))
  - Rename `allowed-script-hosts` to `allowed-script-origins`
  - Change `url-host-allowed?` to `url-origin-allowed?` using prefix matching
  - Merge config origins with user origins

### Phase 2: Popup State and Actions

- [ ] **Add popup state** ([src/popup.cljs](../../src/popup.cljs))
  - `:ui/view` - `:main` or `:settings`
  - `:settings/user-origins` - user-added origins list
  - `:settings/new-origin` - input field value

- [ ] **Add popup actions** ([src/popup_actions.cljs](../../src/popup_actions.cljs))
  - `:popup/ax.show-settings` - switch to settings view
  - `:popup/ax.show-main` - switch to main view
  - `:popup/ax.load-user-origins` - load from storage
  - `:popup/ax.set-new-origin` - update input field
  - `:popup/ax.add-origin` - validate and add origin
  - `:popup/ax.remove-origin` - remove user origin

### Phase 3: UI Components

- [ ] **Add cog icon** ([src/icons.cljs](../../src/icons.cljs))
  - Add `cog` or `settings` icon function
  - Source: [Heroicons cog-6-tooth](https://heroicons.com/) (mini, 20x20)

- [ ] **Add settings UI** ([src/popup.cljs](../../src/popup.cljs))
  - `settings-header` - back arrow + "Settings" title
  - `default-origins-list` - read-only list from config
  - `user-origins-list` - editable list with delete buttons
  - `add-origin-form` - input + add button
  - `settings-view` - combines above components
  - Update `popup-ui` to conditionally render main or settings view

- [ ] **Add header cog button** ([src/popup.cljs](../../src/popup.cljs))
  - Add cog icon button in `.header-right`
  - Click dispatches `:popup/ax.show-settings`

- [ ] **Add CSS styles** ([extension/popup.css](../../extension/popup.css))
  - `.settings-view` container
  - `.settings-header` with back button
  - `.origin-list` for both default and user lists
  - `.origin-item` with delete button styling
  - `.add-origin-form` input and button
  - `.default-origin` read-only styling (muted)

### Phase 4: Testing

- [ ] **Unit tests** ([test/popup_actions_test.cljs](../../test/popup_actions_test.cljs))
  - Test origin add/remove actions
  - Test input validation (must start with http:// or https://, must end with /)

- [ ] **E2E tests** ([e2e/popup_test.cljs](../../e2e/popup_test.cljs))
  - Test settings view toggle
  - Test adding/removing user origins
  - Verify origins persist across popup opens

### Phase 5: Documentation

- [ ] **Update architecture docs** ([dev/docs/architecture.md](architecture.md))
  - Add new storage key to schema
  - Document popup actions

- [ ] **Update README** if needed

## Relevant Documentation

- [Reagami patterns](../../.github/reagami.instructions.md) - UI component patterns
- [Uniflow event system](../../.github/uniflow.instructions.md) - Action/effect patterns
- [Architecture reference](architecture.md) - State schemas, message protocol
- [Heroicons](https://heroicons.com/) - Icon source (cog-6-tooth mini)
- [chrome.storage.local](https://developer.chrome.com/docs/extensions/reference/api/storage) - Storage API

## Validation Rules for User Origins

When adding a new origin:
1. Must start with `http://` or `https://`
2. Must end with `/` or `:` (path prefix or port prefix)
3. Must not be empty after trim
4. Must not duplicate existing origin (config or user)

Example valid entries:
- `https://git.mycompany.com/`
- `https://gitea.internal:3000/`
- `http://localhost:`

## Open Questions

1. Should we show a warning when the user tries to install from a non-whitelisted origin? (Currently just fails with error message)
2. Should there be a "Reset to defaults" button for user origins?
3. Maximum number of user origins? (Probably unnecessary)

## Completion Criteria

- [ ] Cog icon visible in popup header
- [ ] Clicking cog shows settings view
- [ ] Settings view displays default origins (read-only)
- [ ] User can add custom origins
- [ ] User can remove custom origins
- [ ] Origins persist in chrome.storage.local
- [ ] Background worker merges config + user origins for validation
- [ ] Installing from user-added origin succeeds
- [ ] Installing from non-whitelisted origin fails with clear error
- [ ] All existing tests pass
- [ ] New unit tests for origin actions
- [ ] E2E test for settings flow
