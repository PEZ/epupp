# Epupp Architecture Overview

Epupp bridges your Clojure editor to the browser's page execution environment through a multi-layer message relay system.

The architecture handles three main use cases:

1. **REPL Connection** - Live code evaluation from editor via nREPL
2. **Userscript Auto-Injection** - Saved scripts execute on matching URLs
3. **DevTools Panel Evaluation** - Direct evaluation from the panel UI

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
| Source file map + dependencies | [components.md](components.md) |
| Message types + payloads | [message-protocol.md](message-protocol.md) |
| State atoms + action/effect tables | [state-management.md](state-management.md) |
| Uniflow event system | [uniflow.md](uniflow.md) |
| REPL/userscripts/panel injection flows | [injection-flows.md](injection-flows.md) |
| Trust boundaries + CSP strategy | [security.md](security.md) |
| Build pipeline + configuration injection | [build-pipeline.md](build-pipeline.md) |
| CSS architecture + design tokens | [css-architecture.md](css-architecture.md) |

## Related

- Userscript design decisions: [../userscripts-architecture.md](../userscripts-architecture.md)
- Development workflow and build commands: [../dev.md](../dev.md)
