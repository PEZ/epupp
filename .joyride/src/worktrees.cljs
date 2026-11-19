(ns worktrees
  "Starts and stops epupp worktrees in VS Code: runs each worktree's Squint
   Watch, Squint REPL and Babashka REPL as tasks on the worktree's ports, and
   connects Calva to its REPLs as `<name>-squint-repl` and
   `<name>-babashka-repl`. The bbg worktree commands do the checkout work,
   and this namespace asks them for names, ports and what runs.

   Humans start and stop worktrees with the tasks `worktree: start` and
   `worktree: stop`. Agents call `start-branch!`, poll `start-status`, and
   call `stop!`.

   Built from bbg 0cb1d65 (`git -C ~/.config/bbg rev-parse --short HEAD`)."
  (:require ["child_process" :as cp]
            ["net" :as net]
            ["path" :as path]
            ["vscode" :as vscode]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ============================================================
;; What a worktree runs
;; ============================================================

(def ^:private processes
  "The tasks a worktree runs. A keyword in `:process/args` is replaced with
   the worktree's port of that name."
  [{:process/label "Squint Watch" :process/args ["bb" "watch"]}
   {:process/label "Squint REPL" :process/args ["bb" "squint-nrepl" :squint-nrepl]}
   {:process/label "Babashka REPL" :process/args ["bb" "nrepl-server" :bb-nrepl]}])

(def ^:private repls
  "The REPLs Calva connects to, named like the main checkout's sessions."
  [{:repl/port :squint-nrepl :repl/project-type "squint" :repl/session "squint-repl"}
   {:repl/port :bb-nrepl :repl/project-type "babashka" :repl/session "babashka-repl"}])

(def ^:private start-timeout-ms (* 3 60 1000))

(defn- labels [name]
  {:prepare (str "worktree prepare: " name)
   :stop (str "worktree stop: " name)
   :processes (mapv #(str (:process/label %) ": " name) processes)})

(defn- session-key [name repl]
  (str name "-" (:repl/session repl)))

;; ============================================================
;; Asking bbg
;; ============================================================

(defn project-dir
  "The main checkout's folder, the epupp folder of the workspace."
  []
  (some (fn [folder]
          (let [folder-path (.. folder -uri -fsPath)]
            (when (.endsWith folder-path "/epupp") folder-path)))
        vscode/workspace.workspaceFolders))

(defn- worktree-dir [name]
  (path/join (project-dir) "worktrees" name))

(defn- exec+
  "Runs `command` with `args` in `cwd`. Returns a promise of {:exit :out :err},
   also for a non-zero exit."
  [command args cwd]
  (js/Promise.
   (fn [resolve]
     (cp/execFile command (clj->js args) #js {:cwd cwd :maxBuffer (* 10 1024 1024)}
                  (fn [error stdout stderr]
                    (resolve {:exit (if error (or (.-code error) 1) 0)
                              :out (str stdout)
                              :err (str stderr)}))))))

(defn- bbg+ [args]
  (exec+ "bbg" args (project-dir)))

(defn- items+ [kind]
  (.then (bbg+ ["worktree" "items" kind])
         #(edn/read-string (:out %))))

(defn- worktree-name+
  "The name the worktree of `branch` gets. Rejects with bbg's reason when it
   can't get one."
  [branch]
  (.then (bbg+ ["worktree" "name" branch])
         (fn [{:keys [exit out err]}]
           (if (zero? exit)
             (str/trim out)
             (throw (js/Error. (str/trim (str err out))))))))

(defn- ports+
  "The worktree's ports, by port name."
  [name]
  (let [port-names (distinct (map :repl/port repls))]
    (.then (js/Promise.all
            (clj->js (for [port-name port-names]
                       (bbg+ ["worktree" "port" (clojure.core/name port-name) "--worktree" name]))))
           (fn [results]
             (zipmap port-names
                     (map #(js/parseInt (str/trim (:out %)) 10) results))))))

(defn running?+ [name]
  (.then (bbg+ ["worktree" "running" name]) #(zero? (:exit %))))

(defn- prepare-args
  "The arguments `bbg worktree prepare` and `bbg worktree check` take."
  [{:worktree/keys [name ref new-branch?]}]
  (cond-> [name ref]
    new-branch? (conj "--new-branch")))

;; ============================================================
;; Tasks
;; ============================================================

(defn- shell-task
  "Returns a VS Code task named `label`, running `args` in `cwd`, each
   argument quoted for the shell."
  [{:keys [label cwd args background?]}]
  (let [execution (vscode/ShellExecution. (first args)
                                          (clj->js (map str (rest args)))
                                          #js {:cwd cwd})
        task (vscode/Task. #js {:type "shell" :label label}
                           vscode/TaskScope.Workspace label "worktrees" execution)]
    (set! (.-isBackground task) (boolean background?))
    (set! (.-presentationOptions task)
          #js {:reveal vscode/TaskRevealKind.Silent
               :panel vscode/TaskPanelKind.Dedicated
               :group "worktrees"})
    task))

(defn- process-tasks [name ports]
  (map (fn [{:process/keys [args]} label]
         (shell-task {:label label
                      :cwd (worktree-dir name)
                      :args (mapv #(if (keyword? %) (get ports %) %) args)
                      :background? true}))
       processes
       (:processes (labels name))))

(defn- run-to-end+
  "Runs `task` and returns a promise of its exit code."
  [task]
  (js/Promise.
   (fn [resolve reject]
     (let [!sub (atom nil)]
       (reset! !sub (vscode/tasks.onDidEndTaskProcess
                     (fn [e]
                       (when (= (.-name task) (.. e -execution -task -name))
                         (.dispose @!sub)
                         (resolve (.-exitCode e))))))
       (-> (vscode/tasks.executeTask task)
           (.catch (fn [e] (.dispose @!sub) (reject e))))))))

(defn- terminate-tasks! [task-labels]
  (doseq [label task-labels
          execution (filter #(= label (some-> % .-task .-name))
                            (.-taskExecutions vscode/tasks))]
    (.terminate execution)))

;; ============================================================
;; Waiting
;; ============================================================

(defn- sleep+ [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- wait-for+
  "Polls `pred+` every second until it is truthy. Rejects after `timeout-ms`."
  [what pred+ timeout-ms]
  (let [deadline (+ (js/Date.now) timeout-ms)
        poll (fn poll []
               (-> (js/Promise.resolve (pred+))
                   (.then (fn [ok?]
                            (cond
                              ok? true
                              (> (js/Date.now) deadline) (throw (js/Error. (str "Gave up waiting for " what)))
                              :else (.then (sleep+ 1000) poll))))))]
    (poll)))

(defn- port-listening?+ [port]
  (js/Promise.
   (fn [resolve]
     (let [socket (net/connect #js {:host "127.0.0.1" :port port})]
       (.on socket "connect" (fn [] (.destroy socket) (resolve true)))
       (.on socket "error" (fn [_] (resolve false)))))))

;; ============================================================
;; Calva
;; ============================================================

(defn- calva-repl []
  (some-> (vscode/extensions.getExtension "betterthantomorrow.calva") .-exports .-v1 .-repl))

(defn- session-keys []
  (set (map #(.-replSessionKey %) (or (some-> (calva-repl) .listSessions) []))))

(defn- connected? [name]
  (every? (session-keys) (map #(session-key name %) repls)))

(defn- connect+ [name ports {:repl/keys [port project-type] :as repl}]
  (vscode/commands.executeCommand
   "calva.connect"
   (clj->js {:host "localhost"
             :port (get ports port)
             :connectSequence {:name (str name " " (session-key name repl))
                               :projectType project-type
                               :projectRootPath [(worktree-dir name)]
                               :autoSelectForConnect true
                               :replSessionNames {:primary (session-key name repl)}}})))

(defn- connect-all+
  "Connects Calva to each of the worktree's REPLs, one after the other, and
   waits until Calva has all its sessions."
  [name ports]
  (-> (reduce (fn [p repl] (.then p (fn [_] (connect+ name ports repl))))
              (js/Promise.resolve nil)
              repls)
      (.then (fn [_]
               (wait-for+ "Calva's sessions" #(connected? name) (* 60 1000))))))

(defn- disconnect+
  "Disconnects each Calva connection that has one of the worktree's sessions."
  [name]
  (let [keys (set (map #(session-key name %) repls))
        client-keys (keep (fn [connection]
                            (when (some keys (.-replSessionKeys connection))
                              (.-clientKey connection)))
                          (or (some-> (calva-repl) .listConnections) []))]
    (js/Promise.all
     (clj->js (for [client-key client-keys]
                (vscode/commands.executeCommand "calva.disconnect" #js {:clientKey client-key}))))))

;; ============================================================
;; Starting and stopping
;; ============================================================

(defn- end-tasks+ [name]
  (-> (disconnect+ name)
      (.then (fn [_]
               (terminate-tasks! (:processes (labels name)))
               (sleep+ 1000)))))

(defn- prepare-task [worktree]
  (shell-task {:label (:prepare (labels (:worktree/name worktree)))
               :cwd (project-dir)
               :args (into ["bbg" "worktree" "prepare"] (prepare-args worktree))}))

(defn- stop-task [name]
  (shell-task {:label (:stop (labels name))
               :cwd (project-dir)
               :args ["bbg" "worktree" "stop" name]}))

(defn- check+
  "Rejects with bbg's reason when the worktree can't take its ref."
  [worktree]
  (.then (bbg+ (into ["worktree" "check"] (prepare-args worktree)))
         (fn [{:keys [out err]}]
           (let [problem (str/trim (str out err))]
             (when (seq problem)
               (throw (js/Error. problem)))))))

(defn- prepare+
  "Prepares the checkout in a visible task, so a failure's reason can be read."
  [{:worktree/keys [name] :as worktree}]
  (.then (run-to-end+ (prepare-task worktree))
         (fn [exit-code]
           (when-not (zero? exit-code)
             (throw (js/Error. (str "Preparing failed. The terminal \"" (:prepare (labels name)) "\" says why.")))))))

(defn- start-processes+ [name ports]
  (js/Promise.all (clj->js (map #(vscode/tasks.executeTask %) (process-tasks name ports)))))

(defn- all-listening?+ [ports]
  (.then (js/Promise.all (clj->js (map port-listening?+ (vals ports))))
         (fn [results] (every? true? results))))

(defn ^:async start-steps!
  "Checks, stops, prepares, starts and connects the worktree, reporting each
   step to `progress`."
  [{:worktree/keys [name] :as worktree} progress]
  #_{:clj-kondo/ignore [:redundant-do]}
  (do ;; the do makes every await count: an ^:async body awaits only its last form
    (await (check+ worktree))
    (.report progress #js {:message "stopping what runs"})
    (await (end-tasks+ name))
    (.report progress #js {:message "preparing the checkout"})
    (await (prepare+ worktree))
    (let [ports (await (ports+ name))]
      (.report progress #js {:message "starting Squint Watch and the REPLs"})
      (await (start-processes+ name ports))
      (.report progress #js {:message "waiting for the REPLs"})
      (await (wait-for+ "the REPL servers" #(all-listening?+ ports) start-timeout-ms))
      (.report progress #js {:message "connecting Calva"})
      (await (connect-all+ name ports))
      ports)))

(defonce !starts (atom {}))

(defn start-status
  "How the latest start of the worktree `name` goes: `:start/status` is
   `:starting`, `:running` or `:failed`, with `:start/step`, `:start/ports`
   or `:start/error`."
  [name]
  (get @!starts name))

(defn start!
  "Starts the worktree, showing progress. Returns at once. Messages are shown
   outside the awaited steps, since awaiting one waits for the human."
  [{:worktree/keys [name] :as worktree}]
  (swap! !starts assoc name {:start/status :starting})
  (vscode/window.withProgress
   #js {:location vscode/ProgressLocation.Notification :title (str "Worktree " name)}
   (fn [progress]
     (let [reporter #js {:report (fn [m]
                                   (swap! !starts assoc-in [name :start/step] (.-message m))
                                   (.report progress m))}]
       (-> (start-steps! worktree reporter)
           (.then (fn [ports]
                    (swap! !starts assoc name {:start/status :running :start/ports ports})
                    (vscode/window.showInformationMessage
                     (str "Worktree " name " runs. Calva has "
                          (str/join " and " (map #(session-key name %) repls)) "."))
                    nil))
           (.catch (fn [e]
                     (swap! !starts assoc name {:start/status :failed :start/error (.-message e)})
                     (vscode/window.showErrorMessage (str "Worktree " name ": " (.-message e)))
                     nil))))))
  nil)

(defn ^:async stop!
  "Disconnects Calva from the worktree, ends its tasks and processes, and
   resolves once it no longer runs."
  [name]
  #_{:clj-kondo/ignore [:redundant-do]}
  (do ;; the do makes every await count
    (await (end-tasks+ name))
    (await (run-to-end+ (stop-task name)))
    (await (wait-for+ "the processes to stop" #(.then (running?+ name) not) (* 60 1000)))
    (swap! !starts dissoc name)
    :stopped))

;; ============================================================
;; For agents
;; ============================================================

(defn ^:async start-branch!
  "Starts the worktree of `branch` unless it already runs, and returns its
   start status at once. With `:new-branch? true`, the branch is made from
   the main checkout's branch. Poll `start-status` with the worktree's name
   until it says `:running` or `:failed`."
  ([branch] (start-branch! branch {}))
  ([branch {:keys [new-branch?]}]
   (let [name (await (worktree-name+ branch))]
     (if (await (running?+ name))
       {:worktree/name name :start/status :already-running}
       (do (start! {:worktree/ref branch
                    :worktree/name name
                    :worktree/new-branch? (boolean new-branch?)})
           (assoc (start-status name) :worktree/name name))))))

;; ============================================================
;; Pickers, for the tasks
;; ============================================================

(defn- ->quick-pick-items [items]
  (clj->js (map #(select-keys % [:label :description]) items)))

(defn- pick+
  "Shows a QuickPick of `items`, maps with `:label`, `:description` and
   `:data`, and returns a promise of the chosen item's `:data`, or nil.
   `typed-item` makes an item of a typed value no item has as its label.
   `refresh+` returns a promise of new items, shown when it resolves."
  [{:keys [title placeholder items typed-item refresh+]}]
  (js/Promise.
   (fn [resolve]
     (let [qp (vscode/window.createQuickPick)
           !items (atom items)
           !shown (atom [])
           render! (fn []
                     (let [value (str/trim (.-value qp))
                           typed (when (and typed-item (seq value)
                                            (not-any? #(= value (:label %)) @!items))
                                   [(typed-item value)])
                           shown (vec (concat typed @!items))]
                       (reset! !shown shown)
                       (set! (.-items qp) (->quick-pick-items shown))))]
       (set! (.-title qp) title)
       (set! (.-placeholder qp) placeholder)
       (set! (.-matchOnDescription qp) true)
       (.onDidChangeValue qp (fn [_] (render!)))
       (.onDidAccept qp (fn []
                          (let [label (some-> (first (.-selectedItems qp)) .-label)]
                            (resolve (:data (some #(when (= label (:label %)) %) @!shown)))
                            (.hide qp))))
       (.onDidHide qp (fn [] (resolve nil) (.dispose qp)))
       (render!)
       (.show qp)
       (when refresh+
         (set! (.-busy qp) true)
         (-> (refresh+)
             (.then (fn [new-items] (reset! !items new-items) (render!)))
             (.catch (fn [_] nil))
             (.finally (fn [] (set! (.-busy qp) false)))))))))

(defn- branch-items [{:keys [items]}]
  (for [{:worktree/keys [ref name exists? running?]} items]
    {:label ref
     :description (str "worktree " name
                       (cond running? ", running"
                             exists? ", prepared"
                             :else ""))
     :data {:worktree/ref ref :worktree/name name}}))

(defn- fetch-then+ [items-fn+]
  (-> (exec+ "git" ["fetch" "origin"] (project-dir))
      (.catch (fn [_] nil))
      (.then (fn [_] (items-fn+)))))

(defn ^:async start-worktree!
  "Asks for a branch, or a new branch name, and starts its worktree."
  []
  (let [branches (await (items+ "worktree"))
        current (:current-branch branches)
        choice (await (pick+ {:title "Start a worktree"
                              :placeholder "Pick a branch, or type a new branch name"
                              :items (branch-items branches)
                              :typed-item (fn [value]
                                            {:label value
                                             :description (str "new branch from " current)
                                             :data {:worktree/ref value :worktree/new-branch? true}})
                              :refresh+ #(fetch-then+ (fn [] (.then (items+ "worktree") branch-items)))}))]
    (when choice
      (start-branch! (:worktree/ref choice) {:new-branch? (:worktree/new-branch? choice)}))))

(defn ^:async stop-worktree!
  "Asks for a running worktree and stops it."
  []
  (let [{:keys [items]} (await (items+ "running"))
        choice (await (pick+ {:title "Stop a worktree"
                              :placeholder (if (seq items) "Pick a running worktree" "No worktree runs")
                              :items (for [{:worktree/keys [name ref]} items]
                                       {:label name :description ref :data name})}))]
    (when choice
      (await (stop! choice))
      (vscode/window.showInformationMessage (str "Worktree " choice " stopped."))
      nil)))

(comment
  (start-branch! "some-branch")
  (start-branch! "pez/new-thing" {:new-branch? true})
  (start-status "new-thing")
  (stop! "new-thing")
  :rcf)
