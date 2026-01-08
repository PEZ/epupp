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
- **Pencil icon** - Send to DevTools panel for editing
- **Play icon** - Run the script on the current page
- **X icon** - Delete script (not shown for built-in scripts)

### Approval Workflow
When visiting a page that matches an enabled script's URL pattern for the first time:
1. The script appears in "Matching Scripts" with an amber border
2. **Allow** and **Deny** buttons appear
3. Click **Allow** to approve the pattern and run the script
4. Click **Deny** to disable the script

## Script Editor (DevTools Panel)

### Creating a New Script
1. Fill in name, match pattern, and code
2. Click **Save Script** - creates a new script

### Editing an Existing Script
Load a script from popup (click Edit button), then:

**Update code/pattern (keep same name):**
- Button shows **Save Script**
- Click - updates the existing script

**Forking/copy:**
- Change the name
- Button changes to **Create Script**
- **Rename** button also appears
- Click **Create Script** - creates new script, original untouched

**Rename in place (change the name):**
- Click **Rename** - updates name only, same ID preserved
- Status shows "Renamed to..."

### Built-in Scripts
- Cannot be overwritten (Save disabled when name unchanged)
- Can be forked by changing name - **Create Script**

### ID Behavior (under the hood)
- IDs are timestamp-based (e.g., `script-1736294400000`)
- IDs never change once created
- Rename updates script “file“ name only
- Fork creates new ID
