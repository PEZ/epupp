(ns scittle-repl
  "Start a Scittle REPL for experimenting with Scittle code.

   This script:
   1. Starts the browser-nrepl relay server (via VS Code task)
   2. Opens a Calva Flare webview with Scittle + nREPL client
   3. Prompts to connect Calva to the nREPL port

   Usage: Run this script, then use Calva Connect with 'Scittle Dev REPL' sequence."
  (:require [joyride.core :as joyride]
            [joyride.flare :as flare]
            [promesa.core :as p]
            ["vscode" :as vscode]))

;; High port numbers to avoid conflicts with other services
(def nrepl-port 31337)
(def websocket-port 31338)
(def flare-key :sidebar-1)

(defn find-task-by-label [label tasks]
  (first (filter #(= label (.-name %)) tasks)))

(defn relay-running?+
  "Check if browser-nrepl relay is already listening on the nREPL port."
  []
  (p/create
   (fn [resolve _reject]
     (let [net (js/require "net")
           client (.connect net nrepl-port "127.0.0.1")]
       (.on client "connect"
            (fn []
              (.end client)
              (resolve true)))
       (.on client "error"
            (fn [_err]
              (resolve false)))))))

(defn start-browser-nrepl-task!+ []
  (p/let [running? (relay-running?+)]
    (if running?
      (println "Browser-nrepl relay already running on port" nrepl-port)
      (p/let [all-tasks (vscode/tasks.fetchTasks)
              task (find-task-by-label "Scittle Dev REPL" all-tasks)]
        (if task
          (do
            (println "Starting Scittle Dev REPL task...")
            (vscode/tasks.executeTask task))
          (do
            (println "Task 'Scittle Dev REPL' not found. Please add it to .vscode/tasks.json")
            (throw (js/Error. "Task not found"))))))))

(defn open-scittle-flare!+ []
  (println "Opening Scittle flare webview...")
  (flare/flare!+
   {:html [:html
           [:head
            [:meta {:charset "UTF-8"}]
            [:title "Scittle Dev REPL"]
            [:style "
              body {
                font-family: var(--vscode-font-family);
                padding: 20px;
                background: var(--vscode-editor-background);
                color: var(--vscode-editor-foreground);
              }
              h1 { color: var(--vscode-textLink-foreground); }
              .status {
                padding: 10px;
                border-radius: 4px;
                background: var(--vscode-textBlockQuote-background);
                margin: 10px 0;
              }
              .port {
                font-family: var(--vscode-editor-font-family);
                background: var(--vscode-textCodeBlock-background);
                padding: 2px 6px;
                border-radius: 3px;
              }
              code {
                font-family: var(--vscode-editor-font-family);
                background: var(--vscode-textCodeBlock-background);
                padding: 2px 6px;
                border-radius: 3px;
              }
            "]
            ;; Configure Scittle nREPL connection
            [:script (str "var SCITTLE_NREPL_WEBSOCKET_PORT = " websocket-port ";
                          var SCITTLE_NREPL_WEBSOCKET_HOST = '127.0.0.1';")]
            ;; Load Scittle and nREPL client
            [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js"
                      :type "application/javascript"}]
            [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.nrepl.js"
                      :type "application/javascript"}]]
           [:body
            [:h1 "Scittle Dev REPL"]
            [:div.status
             [:p "WebSocket port: " [:span.port (str websocket-port)]]
             [:p "nREPL port: " [:span.port (str nrepl-port)]]]
            [:p "This webview hosts a Scittle runtime connected to the browser-nrepl relay."]
            [:p "To evaluate Scittle code:"]
            [:ol
             [:li "Connect Calva using " [:code "Scittle Dev REPL"] " sequence"]
             [:li "Evaluate ClojureScript code from any file"]
             [:li "Results execute in this Scittle environment"]]
            [:hr]
            [:p [:em "Tip: Keep this panel open while developing Scittle code."]]]]
    :key flare-key
    :title "Scittle Dev REPL"}))

(defn prompt-calva-connect!+ []
  (p/let [choice (vscode/window.showInformationMessage
                  (str "Scittle REPL ready on port " nrepl-port ". Connect Calva now?")
                  "Connect" "Later")]
    (when (= choice "Connect")
      (vscode/commands.executeCommand "calva.connect" #js {:connectSequence "Scittle Dev REPL"}))))

(defn start!+ []
  (p/do!
   ;; Close any existing flare first
   (flare/close! flare-key)
   ;; Start the relay server
   (start-browser-nrepl-task!+)
   ;; Give the server time to start
   (p/delay 2000)
   ;; Open the Scittle flare
   (open-scittle-flare!+)
   ;; Give flare time to connect
   (p/delay 1000)
   ;; Prompt to connect Calva
   (prompt-calva-connect!+)
   (println "Scittle Dev REPL setup complete!")))

(defn stop!+ []
  (p/do!
   (flare/close! flare-key)
   (println "Scittle Dev REPL stopped.")))

;; Run when script is invoked directly
(when (= (joyride/invoked-script) joyride/*file*)
  (start!+))

(comment
  ;; Manual control
  (start!+)
  (stop!+)

  ;; Test flare separately
  (open-scittle-flare!+)
  (flare/close! flare-key)

  ;; List active flares
  (flare/ls)
  :rcf)
