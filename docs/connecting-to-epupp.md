# How to connect REPL Clients to Epupp

The REPL client (your editor or AI harness) needs to support [nREPL](https://nrepl.org). From there it is a matter of connecting to the nREPL port that the [browser-nrepl](https://github.com/babashka/sci.nrepl) relay has been started on. The mechanics for this will differ depending on the editor/AI harness used.

## Prerequisistes

To create a predictable and Epupp-friendly environment for these instructions, we will assume that you have your copy of **my-epupp-hq** cloned to your computer. You also need [Babashka](https://babashka.org).

0. From the https://github.com/PEZ/my-epupp-hq page click **Use this Template** and clone your copy of that reporsitory to your computer.
0. Install [Babashka](https://babashka.org)

## VS Code with Calva

This project has [VS Code](https://code.visualstudio.com/) and [Calva](https://calva.io) configuration for starting and connecting multiple browser-nrepl relays to some common sites.

0. Open your copy of **my-epupp-hq** in VS Code
0. Run the default Build Task: <kbd>cmd/ctrl</kbd>+<kbd>b</kbd>, this starts the relays, one of them is for the default Epupp port 1339.
0. Connect the browser tab to browser-nrepl: Click **Connect** in the Epupp extension's popup UI.
1. In VS Code, install the Calva extension
1. Click the REPL button that appears in the VS Code status bar and select **Connect to a running REPL in your project**
1. Select **my-epupp-hq** in the Project Root menu
1. Select **Epupp REPL** from the **Project Type** menu

That's it. You should see a green session indicator with `epupp-default` in the status bar. To convince yourself that you really have VS Code connected to the tab:

1. In the `userscripts/hq/hello_world.cljs` place the cursor in/near the code you want to evaluate and press <kbd>alt</kbd>+<kbd>enter</kbd>
1. Check in the browser what happened

The configuration leverages the fact that Epupp can be made to use different default ports per domain. If you edit the ports in the Epupp panel before connecting, Epupp will remember that port for the current domain. The configuration lives in two files:
* [.vscode/tasks.json](.vscode/tasks.json). The browser-nrepl tasks for: GitHub, GitLab, YouTube, Ebay, and a default (all other sites).
* [.vscode/settings.json](.vscode/settings.json). The Calva REPL Connect sequences for these tasks.

To use these as provided, check `tasks.json` for the ports used and enter them in the Epupp popup UI per site. But really, they are just suggestions. Add config for your favorite sites and use whatever ports you think make sense. Note that the nREPL port needs to be synced between `tasks.json` and `settings.json`.

Please see [calva.io](https://calva.io) for how to get started and use Calva.

### Copilot with Calva Backseat Driver

Ready to let the AI Agent hack the web for you? Assuming you did the old-fashioned Human Intelligence steps above ([VS Code with Calva](#vs-code-with-calva)):

0. Install the [Copilot](https://marketplace.visualstudio.com/items?itemName=GitHub.copilot) extension in VS Code
1. Install [Calva Backseat Driver](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva-backseat-driver) in VS Code
1. In the Copilot chat view select the **Epupp Assistant** agent
   * For model, Opus 4.5-6 is recommended, but Sonnet 4.5-6 and GPT 5.3 Codex also work fine. Avoid **Auto**, because VS Code will probably select some lame model that does not understand how to use the Epupp REPL.
1. Ask Copilot to use the `epupp-default` REPL to do something fun with the web page you are connected to, or just show you that it can do something.

### VS Code/Cursor with [ECA](https://github.com/editor-code-assistant/eca)

TBD: PRs welcome

### [Cursor](https://www.cursor.com/) with Calva Backseat Driver

TBD: PRs welcome

### Cursor with [clojure-mcp](https://github.com/bhauman/clojure-mcp)

TBD: PRs welcome

## [Emacs](https://www.gnu.org/software/emacs/) with [CIDER](https://cider.mx/)

TBD: PRs welcome

## VIM

TBD: PRs welcome

## [IntelliJ](https://www.jetbrains.com/idea/) with [Cursive](https://cursive-ide.com/)

TBD: PRs welcome

## Your Favorite Editor

TBD: PRs welcome

## [Claude Code](https://docs.anthropic.com/en/docs/claude-code)

TBD: PRs welcome

## Your Favorite AI Agent Harness

TBD: PRs welcome

## PRs Welcome

Please help with making Epupp easy to use with your favorite editor/AI harness by providing instructions and configuration. (I am the creator of [Calva](https://calva.io) and only really have know-how/bandwidth for VS Code and Copilot.)