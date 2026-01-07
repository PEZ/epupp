# UI User Guide

How things work for the end user.

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
