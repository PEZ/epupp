# GitHub and GitLab Page Detection Research

Research conducted: February 1, 2026

## Executive Summary

GitHub and GitLab have distinct page structures and detection patterns. GitHub uses traditional DOM with some React components for repo files, while GitLab uses Vue-based rendering. Both sites can be reliably detected via meta tags and body attributes.

## 1. Site Detection

### GitHub Detection

**Primary indicators:**
- `<meta name="hostname" content="github.com">` (repo files)
- `<meta name="hostname" content="gist.github.com">` (gists)
- Body class: `class="logged-out env-production page-responsive"`
- Route patterns in meta tags:
  - Gist: `<meta name="route-pattern" content="/:user_id/:gist_id(.:format)">`
  - Repo: `<meta name="route-pattern" content="/:user_id/:repository/blob/*name(/*path)">`

**Detection strategy:**
```javascript
function isGitHub() {
  const hostname = document.querySelector('meta[name="hostname"]')?.content;
  return hostname === 'github.com' || hostname === 'gist.github.com';
}

function isGitHubGist() {
  return document.querySelector('meta[name="hostname"]')?.content === 'gist.github.com';
}

function isGitHubRepo() {
  const routePattern = document.querySelector('meta[name="route-pattern"]')?.content;
  return routePattern?.includes('/blob/');
}
```

### GitLab Detection

**Primary indicators:**
- Body attribute: `data-page="snippets:show"` (snippets)
- Body classes include: `gl-browser-generic`, `gl-platform-other`
- Meta tags: `<meta property="og:site_name" content="GitLab">`
- URL patterns: `gitlab.com/-/snippets/` or `gitlab.com/<project>/blob/`

**Detection strategy:**
```javascript
function isGitLab() {
  return document.body?.dataset?.page?.includes('snippet') ||
         window.location.hostname.includes('gitlab.com');
}

function isGitLabSnippet() {
  return document.body?.dataset?.page === 'snippets:show';
}

function isGitLabRepoFile() {
  return window.location.pathname.includes('/blob/') &&
         window.location.hostname.includes('gitlab.com');
}
```

## 2. Code Block Detection by Context

### GitHub Gist

**Structure:**
```html
<div class="js-gist-file-update-container js-task-list-container">
  <div id="file-{filename}" class="file my-2">
    <div class="file-header d-flex flex-md-items-center flex-items-start">
      <div class="file-actions flex-order-2 pt-0">
        <!-- Raw button, etc -->
      </div>
      <div class="file-info ...">
        <!-- File name, etc -->
      </div>
    </div>
    <div class="Box-body p-0 blob-wrapper data type-{lang} gist-border-0">
      <div class="js-blob-code-container blob-code-content">
        <table class="highlight tab-size js-file-line-container">
          <!-- Code lines -->
        </table>
      </div>
    </div>
  </div>
</div>
```

**Selectors:**
- Container: `.js-gist-file-update-container`
- File wrapper: `.file` (with id `file-{filename}`)
- Code container: `.blob-wrapper.data`
- Code content: `.js-blob-code-container.blob-code-content`

**Key characteristics:**
- Each file has unique ID: `file-{filename}`
- Code type indicated by class on blob-wrapper: `type-clojure`, `type-javascript`, etc.
- Traditional table-based code display

### GitHub Repo File

**Structure:**
```html
<!-- React-based code view -->
<div data-ssr-id="react-code-view" class="react-app">
  <!-- Dynamically rendered by React -->
</div>

<!-- Scripts loaded -->
<script src=".../react-code-view-*.js"></script>
<link href=".../react-code-view.*.module.css" />
```

**Selectors:**
- App container: `[data-ssr-id="react-code-view"]`
- Alternative: Look for `react-code-view` in script/CSS URLs

**Key characteristics:**
- **React-rendered content** - DOM structure may not be immediately available
- Need to wait for React hydration
- May use different DOM structure than gists
- File actions may be in React components, not simple `.file-actions` div

**Detection approach:**
```javascript
// Wait for React to render
function findReactCodeView() {
  return new Promise((resolve) => {
    const check = () => {
      const container = document.querySelector('[data-ssr-id="react-code-view"]');
      if (container) {
        resolve(container);
      } else {
        setTimeout(check, 100);
      }
    };
    check();
  });
}
```

### GitLab Snippet

**Structure:**
```html
<body data-page="snippets:show" data-page-type-id="4922251">
  <!-- Vue-based app -->
  <div class="layout-page js-page-layout page-with-super-sidebar">
    <!-- Snippet content rendered by Vue/GraphQL -->
  </div>
</body>

<!-- GraphQL data embedded -->
<script>
  gl.startup_graphql_calls = [{
    query: "query GetSnippetQuery...",
    variables: {"ids": ["gid://gitlab/PersonalSnippet/4922251"]}
  }];
</script>
```

**Selectors:**
- Page identifier: `body[data-page="snippets:show"]`
- Snippet ID: `body[data-page-type-id]`
- Content classes: `.js-snippets-note-edit-form-holder`, `.snippets.note-edit-form`

**Key characteristics:**
- **Vue-based rendering** with GraphQL data
- Content loaded via `gl.startup_graphql_calls`
- May need to wait for Vue to mount
- Snippet data includes file paths in GraphQL response

**Detection approach:**
```javascript
// Check for Vue app mounting
function waitForGitLabSnippet() {
  return new Promise((resolve) => {
    const check = () => {
      const snippetContainer = document.querySelector('.js-snippets-note-edit-form-holder');
      if (snippetContainer) {
        resolve(snippetContainer);
      } else {
        setTimeout(check, 100);
      }
    };
    check();
  });
}
```

### GitLab Repo File

**Similar to snippet structure but:**
- Different `data-page` attribute (likely `projects:blob:show`)
- URL pattern: `/<namespace>/<project>/-/blob/<branch>/<path>`
- May use Monaco editor for editable views

**Note:** No specific example was available during research. Recommend testing with:
- https://gitlab.com/gitlab-org/gitlab/-/blob/master/README.md
- Or any public GitLab repo file

**Expected selectors (to verify):**
- Monaco editor: `.monaco-editor` (for edit mode)
- Blob viewer: `.file-holder`, `.blob-viewer`

## 3. Button Placement Strategy

### GitHub Gist

**Recommended insertion point:**
```javascript
const fileActionsDiv = document.querySelector('.file-actions');
if (fileActionsDiv) {
  // Insert button as first child or before Raw button
  const installButton = createInstallButton();
  fileActionsDiv.insertBefore(installButton, fileActionsDiv.firstChild);
}
```

**Placement characteristics:**
- `.file-actions` is flex container with `flex-order-2`
- Contains Raw button and other file actions
- Located in file header, top-right area
- Multiple files = multiple `.file-actions` divs (one per file)

### GitHub Repo File

**Challenge: React-rendered content**

**Recommended approach:**
```javascript
// Wait for React to render
async function insertButtonInRepoFile() {
  const container = await findReactCodeView();

  // Option 1: Look for Primer button group
  const buttonGroup = container.querySelector('[data-view-component="true"].Button-group');

  // Option 2: Find any action button container
  const actionBar = container.querySelector('[role="toolbar"]');

  if (buttonGroup || actionBar) {
    const target = buttonGroup || actionBar;
    const installButton = createInstallButton();
    target.appendChild(installButton);
  }
}
```

**Placement characteristics:**
- Need to wait for React hydration
- Buttons likely in `Button-group` or toolbar
- May need MutationObserver to detect when buttons appear

### GitLab Snippet

**Recommended insertion point:**
```javascript
// Wait for Vue to render
async function insertButtonInGitLabSnippet() {
  const container = await waitForGitLabSnippet();

  // Look for file header or actions area
  const fileHeader = container.querySelector('.file-title-flex-parent');
  const actionsArea = container.querySelector('.file-actions');

  if (fileHeader || actionsArea) {
    const target = fileHeader || actionsArea;
    const installButton = createInstallButton();
    target.appendChild(installButton);
  }
}
```

**Placement characteristics:**
- Vue-rendered, need to wait for mount
- File actions may be in different structure than GitHub
- Consider using GitLab's button classes: `btn`, `btn-default`

### GitLab Repo File

**Expected approach (to be verified):**
```javascript
// Similar to snippet but look for blob viewer toolbar
const toolbar = document.querySelector('.file-holder .file-title-flex-parent');
if (toolbar) {
  // Insert button
}
```

## 4. Gist vs Snippet vs Repo File Differences

| Aspect | GitHub Gist | GitHub Repo | GitLab Snippet | GitLab Repo |
|--------|-------------|-------------|----------------|-------------|
| **Rendering** | Traditional DOM | React | Vue + GraphQL | Vue + Monaco |
| **Ready state** | DOMContentLoaded | Requires React hydration | Requires Vue mount | Requires Vue mount |
| **File actions** | `.file-actions` | React component | Vue component | Vue component |
| **Code container** | `.blob-wrapper` | React-rendered | Vue-rendered | Vue-rendered |
| **Multi-file** | Yes (multiple `.file` divs) | No | Possibly | No |
| **Detection** | Meta hostname + route | Meta route pattern | Body data-page | URL pattern |

## 5. Implementation Recommendations

### Detection Order

1. **Check hostname/meta tags first** (cheapest operation)
2. **Check URL pattern** (for repo vs gist/snippet)
3. **Check for framework signals** (React/Vue app markers)
4. **Wait for content to load** if framework-based

### Timing Strategy

**GitHub Gist:**
- Safe to run on `DOMContentLoaded`
- Content is in initial HTML

**GitHub Repo:**
- Wait for `react-code-view` to mount
- Use MutationObserver or polling

**GitLab (both):**
- Wait for Vue to mount
- Check for `gl.startup_graphql_calls` completion
- Use MutationObserver on content area

### Code Type Detection

**GitHub:**
- Class on blob-wrapper: `type-clojure`, `type-javascript`
- File extension in file name

**GitLab:**
- Check GraphQL data: `snippet.blobs.nodes[].name`
- File extension in path

**Filter for .cljs files:**
```javascript
function isClojureScriptFile(element) {
  // GitHub Gist
  if (element.classList.contains('type-clojure') ||
      element.classList.contains('type-clojurescript')) {
    return true;
  }

  // Check filename
  const fileName = element.querySelector('.gist-blob-name, .file-info')?.textContent;
  return fileName?.endsWith('.cljs') || fileName?.endsWith('.cljc');
}
```

## 6. Open Questions

1. **GitLab repo file structure** - Need to verify with actual example
2. **Private gists/repos** - Do they have different DOM structure?
3. **GitHub Enterprise** - Same structure as github.com?
4. **GitLab self-hosted** - Same as gitlab.com?
5. **Mobile views** - Different DOM structure?

## 7. Next Steps

1. **Implement detection functions** based on findings above
2. **Test with actual pages** to verify selectors work
3. **Handle loading states** for React/Vue content
4. **Add error handling** for missing elements
5. **Test with .cljs files** specifically to ensure filtering works
6. **Document browser compatibility** (Chrome, Firefox, Safari)

## References

- GitHub Gist: https://gist.github.com/PEZ/9d2a9eec14998de59dde93979453247e
- GitHub Repo: https://github.com/PEZ/browser-jack-in/blob/userscripts/test-data/tampers/repl_manifest.cljs
- GitLab Snippet: https://gitlab.com/-/snippets/4922251
