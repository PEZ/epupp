# Browser Jack-in

Jack into any web page with a Clojure REPL.

## Development

### Setup

```bash
npm install
```

### Build

Build for all browsers:
```bash
npm run build
```

Build for specific browser:
```bash
npm run build:chrome
npm run build:firefox
npm run build:safari
```

### Load Extension Locally

**Chrome:**
1. Go to `chrome://extensions`
2. Enable "Developer mode"
3. Click "Load unpacked"
4. Select `dist/chrome` folder

**Firefox:**
1. Go to `about:debugging#/runtime/this-firefox`
2. Click "Load Temporary Add-on"
3. Select any file in `dist/firefox` folder

**Safari:**
1. Run `xcrun safari-web-extension-converter dist/safari --app-name "Browser Jack-in"`
2. Open the generated Xcode project
3. Build and run

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
