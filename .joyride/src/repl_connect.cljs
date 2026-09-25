(ns repl-connect
  (:require ["vscode" :as vscode]))

(defn session-named? [session-key]
  (boolean
   (some #(= session-key (.-replSessionKey %))
         (or (some-> (vscode/extensions.getExtension "betterthantomorrow.calva")
                     .-exports .-v1 .-repl .listSessions)
             []))))

(defn task-named? [label]
  (boolean
   (some #(= label (some-> % .-task .-name))
         (.-taskExecutions vscode/tasks))))

(defn epupp-task [label tasks]
  (first (filter (fn [task]
                   (and (= label (.-name task))
                        (some-> task .-scope .-uri .-path (.endsWith "/epupp"))))
                 tasks)))

(defn ^:async connect!
  "Connects Calva to a running nREPL using a named connect sequence."
  [{:keys [connect-sequence]}]
  (await (vscode/commands.executeCommand "calva.connect" (clj->js {:connectSequence connect-sequence})))
  "ok")

(defn ^:async start!
  "Starts the connect task, or connects when that nREPL task is already running."
  [{:keys [connect-task repl-task session-key] :as opts}]
  (when-not (session-named? session-key)
    (if (task-named? repl-task)
      (await (connect! opts))
      (let [tasks (await (vscode/tasks.fetchTasks))
            task (epupp-task connect-task tasks)]
        (when task
          (await (vscode/tasks.executeTask task))))))
  "ok")
