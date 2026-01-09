/**
 * Userscript loader for early injection (document-start/document-end).
 *
 * This content script runs in ISOLATED world at document-start.
 * It reads userscripts from storage, finds ones matching the current URL
 * with early timing, and injects them into the page.
 *
 * Flow:
 * 1. Read all scripts from chrome.storage.local
 * 2. Filter to enabled scripts with early timing matching current URL
 * 3. Inject Scittle (synchronous script, blocks until loaded)
 * 4. Inject each script as <script type="application/x-scittle">
 * 5. Inject trigger-scittle.js to evaluate all scripts
 *
 * Note: This runs in ISOLATED world, so we can't directly check window.scittle.
 * We rely on synchronous script loading order (Scittle loads before trigger runs).
 */

(function() {
  'use strict';

  // Guard against multiple injections (per page load)
  if (window.__epuppLoaderInjected) return;
  window.__epuppLoaderInjected = true;

  const currentUrl = window.location.href;
  console.log('[Epupp Loader] Running at', document.readyState, 'for', currentUrl);

  // Test event logging (only active when test mode is enabled)
  // Logs to chrome.storage.local for E2E test assertions
  // Note: Since this file isn't bundled through esbuild, we can't use EXTENSION_CONFIG.
  // Instead, we check a storage flag that the background worker sets at startup.
  function logTestEvent(event, data, testModeEnabled) {
    if (!testModeEnabled) return;
    chrome.storage.local.get(['test-events'], (result) => {
      const events = result['test-events'] || [];
      events.push({
        event: event,
        ts: Date.now(),
        perf: performance.now(),
        data: data
      });
      chrome.storage.local.set({ 'test-events': events });
    });
  }

  // URL pattern matching (mirrors script_utils.cljs logic)
  function patternToRegex(pattern) {
    if (pattern === '<all_urls>') {
      return /^https?:\/\/.*$/;
    }
    // Escape regex special chars except *
    const escaped = pattern.replace(/[.+?^${}()|[\]\\]/g, '\\$&');
    // Convert * to .*
    const withWildcards = escaped.replace(/\*/g, '.*');
    return new RegExp('^' + withWildcards + '$');
  }

  function urlMatchesPattern(url, pattern) {
    return patternToRegex(pattern).test(url);
  }

  function urlMatchesAnyPattern(url, patterns) {
    return patterns && patterns.some(p => urlMatchesPattern(url, p));
  }

  // Check if script should run now based on timing
  function shouldRunNow(script) {
    const runAt = script.runAt || 'document-idle';
    // Early injection only handles document-start and document-end
    return runAt === 'document-start' || runAt === 'document-end';
  }

  // Check if script is enabled and has approved pattern matching current URL
  function scriptMatchesUrl(script, url) {
    if (!script.enabled) return false;
    if (!shouldRunNow(script)) return false;

    // Only run on approved patterns
    const approvedPatterns = script.approvedPatterns || [];
    return urlMatchesAnyPattern(url, approvedPatterns);
  }

  // Inject a script tag into the page (synchronous by default)
  function injectScript(src) {
    const script = document.createElement('script');
    script.src = src;
    // Append to documentElement if head doesn't exist yet (document-start)
    (document.head || document.documentElement).appendChild(script);
    return script;
  }

  // Inject userscript code as Scittle script tag
  function injectUserscript(id, code) {
    const script = document.createElement('script');
    script.type = 'application/x-scittle';
    script.id = id;
    script.dataset.epuppUserscript = 'true';
    script.textContent = code;
    (document.head || document.documentElement).appendChild(script);
    console.log('[Epupp Loader] Injected userscript:', id);
  }

  // Main loader logic
  async function loadScripts() {
    try {
      // Read scripts and test-mode flag from storage
      // test-mode is set by background worker when EXTENSION_CONFIG.test is true
      const result = await chrome.storage.local.get(['scripts', 'test-mode']);
      const scripts = result.scripts || [];
      const testModeEnabled = result['test-mode'] === true;

      // Log that loader is running (for timing tests)
      logTestEvent('LOADER_RUN', {
        url: currentUrl,
        readyState: document.readyState
      }, testModeEnabled);

      // Filter to matching scripts
      const matchingScripts = scripts.filter(s => scriptMatchesUrl(s, currentUrl));

      if (matchingScripts.length === 0) {
        console.log('[Epupp Loader] No matching early scripts for', currentUrl);
        return;
      }

      console.log('[Epupp Loader] Found', matchingScripts.length, 'matching scripts');

      // Inject Scittle first (synchronous script tag)
      // Script tags without async/defer execute in order, so Scittle will be
      // fully loaded before the trigger script runs
      const scittleUrl = chrome.runtime.getURL('vendor/scittle.js');
      const scittleStartTime = performance.now();
      const scittleScript = injectScript(scittleUrl);

      // Listen for Scittle load completion to measure timing
      scittleScript.onload = () => {
        const loadTime = (performance.now() - scittleStartTime).toFixed(1);
        console.log('[Epupp Loader] Scittle loaded in', loadTime, 'ms (document:', document.readyState + ')');
      };

      // Inject each userscript as <script type="application/x-scittle">
      // These don't execute immediately - they wait for Scittle's eval_script_tags()
      for (const script of matchingScripts) {
        const scriptId = 'userscript-' + script.id;
        injectUserscript(scriptId, script.code);
      }

      // Trigger Scittle to evaluate the injected scripts
      // This runs after Scittle is loaded (synchronous execution order)
      const triggerUrl = chrome.runtime.getURL('trigger-scittle.js');
      injectScript(triggerUrl);

      console.log('[Epupp Loader] All scripts injected');
    } catch (err) {
      console.error('[Epupp Loader] Error:', err);
    }
  }

  // Run the loader
  loadScripts();
})();
