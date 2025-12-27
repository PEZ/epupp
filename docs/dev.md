## Development

### Prerequisites

- [Babashka](https://babashka.org/) (bb)
- Node.js (for npm dependencies)

### Setup

```bash
npm install
```

### Build

Build for all browsers:
```bash
bb build:all
```

Build for specific browser:
```bash
bb build:chrome
bb build:firefox
bb build:safari
```

### Load Extension Locally

**Chrome:**

0. Unpack `browser-jack-in-chrome.zip` (will unpack a `chrome` folder)
1. Go to `chrome://extensions`
2. Enable "Developer mode"
3. Click "Load unpacked"
4. Select the `chrome` folder

**Firefox:**

1. Go to `about:debugging#/runtime/this-firefox`
2. Click "Load Temporary Add-on"
3. Select any file in `browser-jack-in-firefox.zip` file

**Safari:**

(Actually the extension fails to establish the websocket connection in Safari. It tries to open it as a secure socket. If you know how to fix it, please file a PR.)

1. Safari → Settings → Developer → Click "Add Temporary Extension"
2. Select the `browser-jack-in-safari.zip` file
3. Ensure the extension is enabled in Safari → Settings → Extensions

## Distribution

Push a tag to create a release:

```bash
git tag v0.1.0
git push origin v0.1.0
```

GitHub Actions will build all three browser versions and create a draft release.

### Store Submission

- **Chrome:** Upload `browser-jack-in-chrome.zip` to [Chrome Web Store Developer Dashboard](https://chrome.google.com/webstore/devconsole)
- **Firefox:** Upload `browser-jack-in-firefox.zip` to [Firefox Add-on Developer Hub](https://addons.mozilla.org/developers/)
- **Safari:** Submit via Xcode to App Store Connect

## Icons

Add your icons to `src/icons/`:
- `icon-16.png` (16x16)
- `icon-32.png` (32x32)
- `icon-48.png` (48x48)
- `icon-128.png` (128x128)
