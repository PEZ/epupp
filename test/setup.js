// Vitest setup file: provide minimal browser global stubs for tests
// that import modules with browser API dependencies (e.g., content_bridge).

// Stub 'window' if not defined (Node.js environment)
if (typeof globalThis.window === 'undefined') {
  globalThis.window = {
    __browserJackInContentBridge: true, // Skip initialization code
    addEventListener: () => {},
    postMessage: () => {},
    location: { href: 'http://test' },
  };
}

// Stub 'chrome' extension API
if (typeof globalThis.chrome === 'undefined') {
  globalThis.chrome = {
    runtime: {
      sendMessage: () => {},
      onMessage: { addListener: () => {} },
      getURL: (path) => `chrome-extension://test-id/${path}`,
    },
    storage: {
      local: { get: (keys, cb) => cb && cb({}), set: (obj, cb) => cb && cb() },
      onChanged: { addListener: () => {} },
    },
  };
}
