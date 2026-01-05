(ns popup-utils
  "Pure utility functions for the popup UI.
   No browser dependencies - takes config/state as arguments.")

;; ============================================================
;; Status display
;; ============================================================

(defn status-class
  "Map status string to CSS class for styling"
  [status]
  (when status
    (cond
      (or (.startsWith status "Failed") (.startsWith status "Error")) "status status-failed"
      (or (.endsWith status "...") (.includes status "not connected")) "status status-pending"
      :else "status")))

(defn generate-server-cmd
  "Generate the bb browser-nrepl server command.
   Takes deps-string (from config) and port settings."
  [{:keys [deps-string nrepl-port ws-port]}]
  (str "bb -Sdeps '" deps-string "' "
       "-e '(require (quote [sci.nrepl.browser-server :as server])) "
       "(server/start! {:nrepl-port " nrepl-port " :websocket-port " ws-port "}) "
       "@(promise)'"))

;; ============================================================
;; Script list transformations
;; ============================================================

(defn toggle-script-in-list
  "Toggle enabled state. When disabling, revoke ALL pattern approvals.
   This ensures re-enabling requires fresh approval for each pattern,
   preventing scripts from silently running on forgotten sites."
  [scripts script-id]
  (mapv (fn [s]
          (if (= (:script/id s) script-id)
            (let [new-enabled (not (:script/enabled s))]
              (if new-enabled
                (assoc s :script/enabled true)
                (-> s
                    (assoc :script/enabled false)
                    (assoc :script/approved-patterns []))))
            s))
        scripts))

(defn approve-pattern-in-list
  "Add pattern to script's approved-patterns if not already present."
  [scripts script-id pattern]
  (mapv (fn [s]
          (if (= (:script/id s) script-id)
            (update s :script/approved-patterns
                    (fn [patterns]
                      (let [patterns (or patterns [])]
                        (if (some #(= % pattern) patterns)
                          patterns
                          (conj patterns pattern)))))
            s))
        scripts))

(defn disable-script-in-list
  "Disable a script by setting enabled to false."
  [scripts script-id]
  (mapv (fn [s]
          (if (= (:script/id s) script-id)
            (assoc s :script/enabled false)
            s))
        scripts))

(defn remove-script-from-list
  "Remove script from list by id."
  [scripts script-id]
  (filterv #(not= (:script/id %) script-id) scripts))
