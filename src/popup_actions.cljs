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
          scripts (:scripts/list state)]
      {:uf/fxs [[:popup/fx.delete-script scripts script-id]]})

    :popup/ax.load-current-url
    {:uf/fxs [[:popup/fx.load-current-url]]}

    :popup/ax.approve-script
    (let [[script-id pattern] args
          scripts (:scripts/list state)]
      {:uf/fxs [[:popup/fx.approve-script scripts script-id pattern]]})

    :popup/ax.deny-script
    (let [[script-id] args
          scripts (:scripts/list state)]
      {:uf/fxs [[:popup/fx.deny-script scripts script-id]]})

    :popup/ax.inspect-script
    (let [[script-id] args
          script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
      (when script
        {:uf/db (assoc state :ui/editing-hint-script-id script-id)
         :uf/fxs [[:popup/fx.inspect-script script]
                  [:uf/fx.defer-dispatch [[:db/ax.assoc :ui/editing-hint-script-id nil]] 3000]]}))

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
        {:uf/db (assoc state :settings/error "Must start with http:// or https:// and end with / or :")
         :uf/fxs [[:uf/fx.defer-dispatch [[:db/ax.assoc :settings/error nil]] 3000]]}

        (popup-utils/origin-already-exists? origin default-origins user-origins)
        {:uf/db (assoc state :settings/error "Origin already exists")
         :uf/fxs [[:uf/fx.defer-dispatch [[:db/ax.assoc :settings/error nil]] 3000]]}

        :else
        {:uf/db (-> state
                    (update :settings/user-origins conj origin)
                    (assoc :settings/new-origin "")
                    (assoc :settings/error nil))
         :uf/fxs [[:popup/fx.add-user-origin origin]]}))

    :popup/ax.remove-origin
    (let [[origin] args]
      {:uf/db (update state :settings/user-origins
                      (fn [origins] (filterv #(not= % origin) origins)))
       :uf/fxs [[:popup/fx.remove-user-origin origin]]})

    :popup/ax.load-auto-connect-setting
    {:uf/fxs [[:popup/fx.load-auto-connect-setting]]}

    :popup/ax.toggle-auto-connect-repl
    (let [new-value (not (:settings/auto-connect-repl state))]
      {:uf/db (assoc state :settings/auto-connect-repl new-value)
       :uf/fxs [[:popup/fx.save-auto-connect-setting new-value]]})

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

    :popup/ax.reveal-tab
    (let [[tab-id] args]
      {:uf/fxs [[:popup/fx.reveal-tab tab-id]]})

    :popup/ax.disconnect-tab
    (let [[tab-id] args]
      {:uf/fxs [[:popup/fx.disconnect-tab tab-id]]})

    :uf/unhandled-ax))
