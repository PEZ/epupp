# UI User Guide

How things work for the end user.

## Popup Script Management

### Script Sections
Scripts are organized into two sections based on whether they match the current page URL:

- **Matching Scripts** - Scripts with URL patterns that match the current tab
- **Other Scripts** - All other saved scripts

Within each section, scripts are sorted:
1. User scripts alphabetically
2. Built-in scripts alphabetically (shown last, with grey left border)

### Built-in Script Indicator
Built-in scripts are marked with a cube icon (📦) before the name. Hover over the icon to see the "Built-in script" tooltip.

### Script Actions
Each script has action buttons:
- **Checkbox** - Enable/disable the script
- **Eye icon** - Inspect script (send to DevTools panel for viewing/editing)
- **Play icon** - Run the script on the current page
- **X icon** - Delete script (not shown for built-in scripts)

### Approval Workflow
When visiting a page that matches an enabled script's URL pattern for the first time:
1. The script appears in "Matching Scripts" with an amber border
2. **Allow** and **Deny** buttons appear
3. Click **Allow** to approve the pattern and run the script
4. Click **Deny** to disable the script

## Script Editor (DevTools Panel)

### Manifest-Driven Metadata

Script metadata (name, site match, description, timing) is defined in the code itself using manifest annotations. The panel displays these values in a property table format - always showing all four fields regardless of whether values are specified.

**Example script with manifest:**
```clojure
{:epupp/script-name "GitHub Tweaks"
 :epupp/site-match "https://github.com/*"
 :epupp/description "Enhance GitHub UX"
 :epupp/run-at "document-idle"}

(ns github-tweaks)
(js/console.log "Hello GitHub!")
```

**Property table fields:**
| Field | Source | Notes |
|-------|--------|-------|
| Name | `:epupp/script-name` | Shows normalized ID format (e.g., `my_script.cljs`) |
| URL Pattern | `:epupp/site-match` | String or vector of patterns |
| Description | `:epupp/description` | Shows "Not specified" if omitted |
| Run At | `:epupp/run-at` | Shows "document-idle (default)" if omitted |

**Manifest keys:**
| Key | Required | Description |
|-----|----------|-------------|
| `:epupp/script-name` | Yes | Script name (auto-normalized to ID format) |
| `:epupp/site-match` | Yes | URL pattern - string or vector of strings |
| `:epupp/description` | No | Human-readable description |
| `:epupp/run-at` | No | Injection timing: `"document-start"`, `"document-end"`, or `"document-idle"` (default) |

### Creating a New Script

1. Write your code with a manifest map at the top
2. The panel shows parsed values in a property table:
   - **Name** - from `:epupp/script-name` (shows normalized ID form)
   - **URL Pattern** - from `:epupp/site-match`
   - **Description** - from `:epupp/description` (or "Not specified")
   - **Run At** - from `:epupp/run-at` (or "document-idle (default)")
3. Click **Save Script**

**Hints and warnings:**
- Name normalization: `"My Cool Script"` shown as `my_cool_script.cljs` with hint "(normalized)"
- Invalid run-at values show a warning
- Unknown `:epupp/*` keys are flagged

**No manifest?** The panel shows guidance text explaining the manifest format with a copyable example.

### Editing an Existing Script

Load a script from popup (click Eye icon), then:

1. The code loads into the editor with its manifest
2. Property table shows current metadata values
3. Edit the code (including manifest values) as needed
4. Click **Save Script** to update

**Name changes:**
- Change `:epupp/script-name` in the manifest to rename
- Button stays **Save Script** - the script ID remains the same
- The name field updates to show the new normalized name

### Built-in Scripts

- Loaded read-only for inspection
- Save button is disabled
- To customize: copy the code, change the name in manifest, save as new script

### ID Behavior (under the hood)

- IDs are timestamp-based (e.g., `script-1736294400000`)
- IDs never change once created
- Manifest name changes update the display name, not the ID
- To create a copy: change the manifest name, save creates new script with new ID
