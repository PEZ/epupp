(ns workspace-activate
  (:require [joyride.core :as joyride]
            [repl-connect]
            scittle-repl
            ["vscode" :as vscode]))

(defonce !db (atom {:disposables []}))

;; To make the activation script re-runnable we dispose of
;; event handlers and such that we might have registered
;; in previous runs.
(defn- clear-disposables! []
  (run! (fn [disposable]
          (.dispose disposable))
        (:disposables @!db))
  (swap! !db assoc :disposables []))

;; Pushing the disposables on the extension context's
;; subscriptions will make VS Code dispose of them when the
;; Joyride extension is deactivated.
(defn- push-disposable [disposable]
  (swap! !db update :disposables conj disposable)
  (-> (joyride/extension-context)
      .-subscriptions
      (.push disposable)))

(defn- task-running? [label]
  (boolean
   (some #(= label (some-> % .-task .-name))
         (.-taskExecutions vscode/tasks))))

(defn- ^:async ensure-task!
  "Runs a workspace task by label when it is not already executing."
  [label]
  (when-not (task-running? label)
    (let [tasks (await (vscode/tasks.fetchTasks))
          task (first (filter (fn [t]
                                (and (= label (.-name t))
                                     (some-> t .-scope .-uri .-path (.endsWith "/epupp"))))
                              tasks))]
      (when task
        (await (vscode/tasks.executeTask task)))))
  "ok")

(defn- ^:async my-main []
  (println "Hello World, from my-main workspace_activate.cljs script")
  (clear-disposables!)
  #_(push-disposable
     ;; This is just an example. Remove it when it starts to annoy you.
     (vscode/workspace.onDidOpenTextDocument
      (fn [doc]
        (println "[Joyride example]"
                 (.-languageId doc)
                 "document opened:"
                 (.-fileName doc)))))

  (await (ensure-task! "Squint Watch"))
  (await (ensure-task! "Unit Test Watch"))
  (await (repl-connect/start! {:connect-task "Connect Squint REPL"
                               :repl-task "Squint REPL"
                               :connect-sequence "Squint REPL"
                               :session-key "squint-repl"}))
  (await (repl-connect/start! {:connect-task "Connect Babashka REPL"
                               :repl-task "Babashka REPL"
                               :connect-sequence "Babashka REPL"
                               :session-key "babashka-repl"}))
  (await (scittle-repl/start!+))
  (await (vscode/commands.executeCommand "calva.connect" #js {:connectSequence "Scittle Dev REPL"}))
  "ok")

(when (= (joyride/invoked-script) joyride/*file*)
  (my-main))
