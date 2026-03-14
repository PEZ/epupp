# AMO Build Instructions

Instructions for reproducing the Epupp browser extension build from source.
This document is submitted to Firefox Add-ons (AMO) reviewers alongside the source archive.

## Prerequisites

- **OS**: Ubuntu (CI uses ubuntu-latest)
- **Node.js**: 20 LTS
- **Babashka**: Latest (https://github.com/babashka/babashka#installation)

## Build Steps

```bash
# Install Node.js dependencies
npm ci

# Build release extensions for all browsers
bb build
```

The Firefox extension zip is produced at `dist/epupp-firefox.zip`.

## What the Build Does

1. `bb build` drives the entire pipeline:
   - Downloads Scittle vendor files (cached)
   - Compiles Squint source (`src/*.cljs`) to ES modules (`extension/*.mjs`)
   - Bundles with esbuild into IIFE format (`build/*.js`)
   - Packages browser-specific zips into `dist/`

## Verification

After building, the contents of `dist/epupp-firefox.zip` should match the submitted extension package.
