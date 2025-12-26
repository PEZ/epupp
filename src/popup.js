// Popup script

// Inject a script tag and return immediately
async function injectScript(tabId, url) {
  console.log('[popup] Injecting:', url, 'into tab:', tabId);
  try {
    const result = await chrome.scripting.executeScript({
      target: { tabId },
      world: 'MAIN',
      func: (url) => {
        const script = document.createElement('script');
        script.src = url;
        document.head.appendChild(script);
        console.log('[DOM REPL] Injected:', url);
        return 'ok';
      },
      args: [url]
    });
    console.log('[popup] Injection result:', result);
    return result;
  } catch (err) {
    console.error('[popup] Injection error:', err);
    throw err;
  }
}

// Set the nREPL config (host + port)
function setNreplConfig(tabId, port) {
  return chrome.scripting.executeScript({
    target: { tabId },
    world: 'MAIN',
    func: (port) => {
      window.SCITTLE_NREPL_WEBSOCKET_HOST = 'localhost';
      window.SCITTLE_NREPL_WEBSOCKET_PORT = port;
      console.log('[DOM REPL] Set nREPL to ws://localhost:' + port);
    },
    args: [port]
  });
}

// Poll until Scittle is ready, also detect CSP errors
function waitForScittle(tabId, timeout = 5000) {
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const poll = async () => {
      const [result] = await chrome.scripting.executeScript({
        target: { tabId },
        world: 'MAIN',
        func: () => {
          // Check for CSP error (Scittle sets this on failure)
          if (window.__scittle_csp_error) {
            return { error: 'csp' };
          }
          if (window.scittle && window.scittle.core) {
            return { ready: true };
          }
          // Try to detect if eval is blocked
          try {
            eval('1');
            return { ready: false };
          } catch (e) {
            return { error: 'csp' };
          }
        }
      });

      if (result.result.error === 'csp') {
        reject(new Error('Page blocks eval (CSP). Try a different page.'));
      } else if (result.result.ready) {
        resolve();
      } else if (Date.now() - start > timeout) {
        reject(new Error('Timeout - Scittle failed to load'));
      } else {
        setTimeout(poll, 100);
      }
    };
    poll();
  });
}

document.getElementById('start').addEventListener('click', async () => {
  const nreplPort = parseInt(document.getElementById('nrepl-port').value, 10);
  const wsPort = parseInt(document.getElementById('ws-port').value, 10);
  const statusEl = document.getElementById('status');

  if (isNaN(nreplPort) || nreplPort < 1 || nreplPort > 65535) {
    statusEl.textContent = 'Invalid nREPL port';
    return;
  }
  if (isNaN(wsPort) || wsPort < 1 || wsPort > 65535) {
    statusEl.textContent = 'Invalid WebSocket port';
    return;
  }

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });

  try {
    // Inject Scittle
    statusEl.textContent = 'Loading Scittle...';
    const scittleUrl = chrome.runtime.getURL('vendor/scittle.js');
    await injectScript(tab.id, scittleUrl);

    // Wait for Scittle to initialize
    await waitForScittle(tab.id);

    // Set port and inject nREPL
    statusEl.textContent = 'Starting nREPL...';
    await setNreplConfig(tab.id, wsPort);
    const nreplUrl = chrome.runtime.getURL('vendor/scittle.nrepl.js');
    await injectScript(tab.id, nreplUrl);

    statusEl.textContent = 'Connected! Editor: localhost:' + nreplPort;
  } catch (err) {
    statusEl.textContent = 'Failed: ' + err.message;
    console.error(err);
  }
});
