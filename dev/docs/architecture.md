# Epupp Architecture Overview

Epupp bridges your Clojure editor to the browser's page execution environment through a multi-layer message relay system.

The architecture handles four main use cases:

1. **REPL Connection** - Live code evaluation from editor via nREPL
2. **Userscript Auto-Injection** - Saved scripts execute on matching URLs
3. **DevTools Panel Evaluation** - Direct evaluation from the panel UI
4. **REPL FS Sync** - File operations over the REPL for userscript management

Detailed docs live under [architecture/](architecture/). Use the Navigate table below to jump to the relevant reference.

## Component Architecture

```mermaid
flowchart TB
    subgraph Browser
        subgraph Extension
            BG["Background Worker<br/>- WebSocket mgmt<br/>- Script inject<br/>- Approvals"]
            Popup["Popup<br/>- REPL connect<br/>- Script list<br/>- Approvals UI"]
            Panel["DevTools Panel<br/>- Code eval<br/>- Save script"]

            Popup -->|"chrome.runtime"| BG
            Panel -->|"chrome.runtime"| BG
        end

        CB["Content Bridge (ISOLATED)<br/>- Relay messages<br/>- Inject scripts<br/>- Keepalive pings"]
        BG -->|"chrome.tabs.sendMessage"| CB
        Panel -.->|"inspectedWindow.eval"| Page

        subgraph Page["Page (MAIN world)"]
            WSB["WebSocket Bridge<br/>(virtual WS)"]
            Scittle["Scittle REPL"]
            DOM["DOM"]
            WSB <--> Scittle <--> DOM
        end

        CB -->|"postMessage"| WSB
    end

    Relay["Babashka browser-nrepl<br/>(relay server)"]
    Editor["Editor / AI Agent<br/>(nREPL client)"]

    BG <-->|"ws://localhost:12346"| Relay
    Editor -->|"nrepl://localhost:12345"| Relay
```

**Note:** Panel evaluates code directly in page context via `chrome.devtools.inspectedWindow.eval` (dotted line), but requests Scittle injection via background worker.

## Navigate

| Topic | Read This |
|------|-----------|
| **UI architecture + TDD workflow** | [ui.md](ui.md) |
| Source file map + dependencies | [architecture/components.md](architecture/components.md) |
| Message types + payloads | [architecture/message-protocol.md](architecture/message-protocol.md) |
| REPL FS Sync architecture | [architecture/repl-fs-sync.md](architecture/repl-fs-sync.md) |
| Connected REPL flow + `epupp/manifest!` | [architecture/connected-repl.md](architecture/connected-repl.md) |
| State atoms + action/effect tables | [architecture/state-management.md](architecture/state-management.md) |
| Uniflow event system | [architecture/uniflow.md](architecture/uniflow.md) |
| REPL/userscripts/panel injection flows | [architecture/injection-flows.md](architecture/injection-flows.md) |
| Trust boundaries + CSP strategy | [architecture/security.md](architecture/security.md) |
| Build pipeline + configuration injection | [architecture/build-pipeline.md](architecture/build-pipeline.md) |
| CSS architecture + design tokens | [architecture/css-architecture.md](architecture/css-architecture.md) |

## Related

- Userscript design decisions: [userscripts-architecture.md](userscripts-architecture.md)
- Development workflow and build commands: [dev.md](dev.md)
