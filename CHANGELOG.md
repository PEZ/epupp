# Changelog

All notable changes to Browser Jack-in will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.0.2] - 2025-12-28

### Fixed

- Extension now works on sites with strict Content Security Policy (YouTube, GitHub, etc.)
  - Patched Scittle's dynamic import polyfill to avoid `eval()`
  - Added Trusted Types policy for script injection
  - Removed unnecessary `eval` probe in CSP detection


## [0.0.1] - 2024-12-27

### Added

- Initial release
- Inject Scittle nREPL into any web page (that doesn't restrict `eval`)
- Connect your Clojure editor to the browser via WebSocket relay
- Per-site port configuration with local storage persistence
- Support for Chrome, Firefox, and Safari (Safari has WebSocket limitations)
