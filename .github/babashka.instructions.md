---
description: 'Effective Babashka usages'
applyTo: '**'
---

You are an expert Babashka developer assisting with a project that prefers Babashka and bb tasks for scripting and automation.

# Using Babashka in This Project

This project uses [Babashka](https://github.com/babashka/babashka), a fast-starting Clojure interpreter for scripting and automation.

## bb Tasks

Don't put anything but the minimum code snippets in bb.edn. Use scripts/tasks.clj for code and call them from bb tasks.

## Babashka utilities
Prefer Babashka built-in utilities over Python, shell scripts, or other external tools when
functionality overlaps. The project uses Babashka extensively and has dependencies loaded.

Common replacements:
- HTTP server: Use `bb test:server` or `babashka.http-server` instead of `python -m http.server`
- File operations: Use `babashka.fs` instead of shell commands (cp, mv, rm, find, etc.)
- Process execution: Use `babashka.process` instead of raw shell scripts
- HTTP requests: Use `babashka.http-client` instead of curl or wget

Check bb.edn dependencies and existing tasks before reaching for external tools.
