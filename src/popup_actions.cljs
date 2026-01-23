(ns popup-actions
  "Pure action handlers for the extension popup.
   No browser dependencies - testable without Chrome APIs."
  (:require [popup-utils :as popup-utils]))

(defn handle-action
  "Pure action handler for popup state transitions.
   Returns map with :uf/db, :uf/fxs, :uf/dxs keys.

   uf-data should contain:
   - :system/now - current timestamp
   - :config/deps-string - deps string for server command generation
   - :config/allowed-origins - default allowed origins from config"
  [state uf-data [action & args]]
  (case action
    :popup/ax.set-nrepl-port
    (let [[port] args
          new-state (assoc state :ports/nrepl port)]
      {:uf/db new-state
       :uf/fxs [[:popup/fx.save-ports (select-keys new-state [:ports/nrepl :ports/ws])]]})

    :popup/ax.set-ws-port
    (let [[port] args
          new-state (assoc state :ports/ws port)]
      {:uf/db new-state
       :uf/fxs [[:popup/fx.save-ports (select-keys new-state [:ports/nrepl :ports/ws])]]})

    :popup/ax.copy-command
    (let [deps-string (:config/deps-string uf-data)
          cmd (popup-utils/generate-server-cmd
               {:deps-string deps-string
                :nrepl-port (:ports/nrepl state)
                :ws-port (:ports/ws state)})]
      {:uf/fxs [[:popup/fx.copy-command cmd]]})

    :popup/ax.connect
    (let [port (js/parseInt (:ports/ws state) 10)]
      (when (and (not (js/isNaN port)) (<= 1 port 65535))
        {:uf/fxs [[:popup/fx.connect port]]}))

    :popup/ax.check-status
    {:uf/fxs [[:popup/fx.check-status (:ports/ws state)]]}

    :popup/ax.load-saved-ports
    {:uf/fxs [[:popup/fx.load-saved-ports]]}

    :popup/ax.load-scripts
    {:uf/fxs [[:popup/fx.load-scripts]]}

    :popup/ax.toggle-script
    (let [[script-id matching-pattern] args
          scripts (:scripts/list state)]
      {:uf/fxs [[:popup/fx.toggle-script scripts script-id matching-pattern]]})

    :popup/ax.delete-script
    (let [[script-id] args
          leaving-scripts (or (:ui/leaving-scripts state) #{})]
      (if (contains? leaving-scripts script-id)
        ;; Step 2: After animation, actually delete
        {:uf/db (update state :ui/leaving-scripts disj script-id)
         :uf/fxs [[:popup/fx.delete-script (:scripts/list state) script-id]]}
        ;; Step 1: Mark as leaving, defer actual delete
        {:uf/db (update state :ui/leaving-scripts (fnil conj #{}) script-id)
         :uf/fxs [[:uf/fx.defer-dispatch [[:popup/ax.delete-script script-id]] 250]]}))

    :popup/ax.load-current-url
    {:uf/fxs [[:popup/fx.load-current-url]]}

    :popup/ax.inspect-script
    (let [[script-id] args
          script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
      (when script
        {:uf/fxs [[:popup/fx.inspect-script script]
                  [:uf/fx.defer-dispatch [[:popup/ax.show-system-banner "info" "Open the Epupp panel in Developer Tools"]] 0]]}))

    :popup/ax.evaluate-script
    (let [[script-id] args
          script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
      (when script
        {:uf/fxs [[:popup/fx.evaluate-script script]]}))

    :popup/ax.load-user-origins
    {:uf/fxs [[:popup/fx.load-user-origins]]}

    :popup/ax.set-new-origin
    (let [[value] args]
      {:uf/db (assoc state :settings/new-origin value)})

    :popup/ax.add-origin
    (let [origin (.trim (:settings/new-origin state))
          default-origins (:settings/default-origins state)
          user-origins (:settings/user-origins state)]
      (cond
        (not (popup-utils/valid-origin? origin))
        {:uf/dxs [[:popup/ax.show-system-banner "error" "Must start with http:// or https:// and end with / or :" {}]]}

        (popup-utils/origin-already-exists? origin default-origins user-origins)
        {:uf/dxs [[:popup/ax.show-system-banner "error" "Origin already exists" {}]]}

        :else
        {:uf/db (-> state
                    (update :settings/user-origins conj origin)
                    (assoc :settings/new-origin ""))
         :uf/fxs [[:popup/fx.add-user-origin origin]]}))

    :popup/ax.remove-origin
    (let [[origin] args
          leaving-origins (or (:ui/leaving-origins state) #{})]
      (if (contains? leaving-origins origin)
        ;; Step 2: After animation, actually remove
        {:uf/db (-> state
                    (update :ui/leaving-origins disj origin)
                    (update :settings/user-origins (fn [origins] (filterv #(not= % origin) origins))))
         :uf/fxs [[:popup/fx.remove-user-origin origin]]}
        ;; Step 1: Mark as leaving, defer actual remove
        {:uf/db (update state :ui/leaving-origins (fnil conj #{}) origin)
         :uf/fxs [[:uf/fx.defer-dispatch [[:popup/ax.remove-origin origin]] 250]]}))

    :popup/ax.load-auto-connect-setting
    {:uf/fxs [[:popup/fx.load-auto-connect-setting]]}

    :popup/ax.toggle-auto-connect-repl
    (let [new-value (not (:settings/auto-connect-repl state))]
      {:uf/db (assoc state :settings/auto-connect-repl new-value)
       :uf/fxs [[:popup/fx.save-auto-connect-setting new-value]]})

    :popup/ax.load-auto-reconnect-setting
    {:uf/fxs [[:popup/fx.load-auto-reconnect-setting]]}

    :popup/ax.toggle-auto-reconnect-repl
    (let [new-value (not (:settings/auto-reconnect-repl state))]
      {:uf/db (assoc state :settings/auto-reconnect-repl new-value)
       :uf/fxs [[:popup/fx.save-auto-reconnect-setting new-value]]})

    :popup/ax.load-fs-sync-setting
    {:uf/fxs [[:popup/fx.load-fs-sync-setting]]}

    :popup/ax.toggle-fs-sync
    (let [new-value (not (:settings/fs-repl-sync-enabled state))]
      {:uf/db (assoc state :settings/fs-repl-sync-enabled new-value)
       :uf/fxs [[:popup/fx.save-fs-sync-setting new-value]]})

    :popup/ax.toggle-section
    (let [[section-id] args]
      {:uf/db (update-in state [:ui/sections-collapsed section-id] not)})

    :popup/ax.export-scripts
    {:uf/fxs [[:popup/fx.export-scripts]]}

    :popup/ax.import-scripts
    {:uf/fxs [[:popup/fx.trigger-import]]}

    :popup/ax.handle-import
    (let [[scripts-data] args]
      {:uf/fxs [[:popup/fx.import-scripts scripts-data]]})

    :popup/ax.dump-dev-log
    {:uf/fxs [[:popup/fx.dump-dev-log]]}

    :popup/ax.load-connections
    {:uf/fxs [[:popup/fx.load-connections]]}

    :popup/ax.reveal-script
    (let [[script-name] args
          new-state (-> state
                        ;; Expand script sections so reveal has a chance to find it
                        (assoc-in [:ui/sections-collapsed :matching-scripts] false)
                        (assoc-in [:ui/sections-collapsed :other-scripts] false)
                        (assoc :ui/reveal-highlight-script-name script-name))]
      {:uf/db new-state
       :uf/fxs [[:popup/fx.reveal-script script-name]
                [:uf/fx.defer-dispatch [[:db/ax.assoc :ui/reveal-highlight-script-name nil]] 2000]]})

    :popup/ax.reveal-tab
    (let [[tab-id] args]
      {:uf/fxs [[:popup/fx.reveal-tab tab-id]]})

    :popup/ax.disconnect-tab
    (let [[tab-id] args
          leaving-tabs (or (:ui/leaving-tabs state) #{})]
      (if (contains? leaving-tabs tab-id)
        ;; Step 2: After animation, actually disconnect
        {:uf/db (update state :ui/leaving-tabs disj tab-id)
         :uf/fxs [[:popup/fx.disconnect-tab tab-id]]}
        ;; Step 1: Mark as leaving, defer actual disconnect
        {:uf/db (update state :ui/leaving-tabs (fnil conj #{}) tab-id)
         :uf/fxs [[:uf/fx.defer-dispatch [[:popup/ax.disconnect-tab tab-id]] 250]]}))

    :popup/ax.mark-scripts-modified
    (let [[script-names] args
          current-modified (or (:ui/recently-modified-scripts state) #{})
          new-modified (into current-modified script-names)]
      {:uf/db (assoc state :ui/recently-modified-scripts new-modified)
       :uf/fxs [[:uf/fx.defer-dispatch [[:popup/ax.clear-modified-scripts]] 2000]]})

    :popup/ax.clear-modified-scripts
    {:uf/db (assoc state :ui/recently-modified-scripts #{})}

    ;; System banner actions
    :popup/ax.show-system-banner
    (let [[event-type message bulk-info] args
          {:keys [bulk-op? bulk-final? bulk-names]} bulk-info]
      {:uf/db (assoc state :ui/system-banner {:type event-type :message message})
       :uf/fxs [[:popup/fx.log-system-banner message bulk-op? bulk-final? bulk-names]
                [:uf/fx.defer-dispatch [[:popup/ax.clear-system-banner]] 2000]]})

    :popup/ax.clear-system-banner
    (if (get-in state [:ui/system-banner :leaving])
      ;; Step 2: After animation, clear the banner
      {:uf/db (assoc state :ui/system-banner nil)}
      ;; Step 1: Mark as leaving, defer actual clear
      {:uf/db (assoc-in state [:ui/system-banner :leaving] true)
       :uf/fxs [[:uf/fx.defer-dispatch [[:popup/ax.clear-system-banner]] 250]]})

    :popup/ax.track-bulk-name
    (let [[bulk-id script-name] args]
      {:uf/db (update-in state [:ui/system-bulk-names bulk-id] (fnil conj []) script-name)})

    :popup/ax.clear-bulk-names
    (let [[bulk-id] args]
      {:uf/db (update state :ui/system-bulk-names dissoc bulk-id)})

    :uf/unhandled-ax))