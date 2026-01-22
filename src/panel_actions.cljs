(ns panel-actions
  "Pure action handlers for the DevTools panel.
   No browser dependencies - testable without Chrome APIs."
  (:require [script-utils :as script-utils]
            [manifest-parser :as mp]))

(def default-script
  "Default script shown when panel initializes with no saved state.
   Has a valid manifest with proper name and namespace."
  "{:epupp/script-name \"hello_world.cljs\"
 :epupp/site-match \"https://example.com/*\"
 :epupp/description \"A script saying hello\"}

(ns hello-world)

(defn hello [s]
  (js/console.log \"Epupp: Hello\" (str s \"!\")))

(hello \"World\")")

(defn- build-manifest-dxs
  "Build deferred dispatch actions from manifest data.
   Returns vector of actions to update name, match, description from manifest."
  [manifest]
  (when manifest
    (let [script-name (get manifest "script-name")
          site-match (get manifest "site-match")
          description (get manifest "description")]
      (cond-> []
        script-name (conj [:editor/ax.set-script-name script-name])
        site-match (conj [:editor/ax.set-script-match site-match])
        description (conj [:editor/ax.set-script-description description])))))

(defn- build-manifest-hints
  "Build hints map from manifest for UI display.
   Includes normalization info, unknown keys, validation issues, and require info."
  [manifest]
  (when manifest
    {:name-normalized? (get manifest "name-normalized?")
     :raw-script-name (get manifest "raw-script-name")
     :unknown-keys (get manifest "unknown-keys")
     :run-at-invalid? (get manifest "run-at-invalid?")
     :raw-run-at (get manifest "raw-run-at")
     :run-at (get manifest "run-at")
     :require (get manifest "require")}))

(defn- get-code-to-eval
  "Determine code to evaluate: selection text if present and non-empty, else full code."
  [state]
  (let [selection (:panel/selection state)
        selection-text (when selection (:text selection))]
    (if (seq selection-text)
      selection-text
      (:panel/code state))))

(defn handle-action
  "Pure action handler for panel state transitions.
   Returns map with :uf/db, :uf/fxs, :uf/dxs keys."
  [state uf-data [action & args]]
  (case action
    :editor/ax.set-code
    (let [[code] args
          ;; Parse manifest from code - safely catch any parse errors
          manifest (try (mp/extract-manifest code) (catch :default _ nil))
          ;; Build hints for UI display
          hints (build-manifest-hints manifest)
          ;; Build dxs to update fields from manifest
          dxs (build-manifest-dxs manifest)
          ;; Update state with code and hints
          new-state (-> state
                        (assoc :panel/code code)
                        (assoc :panel/manifest-hints hints))]
      (cond-> {:uf/db new-state}
        (seq dxs) (assoc :uf/dxs dxs)))

    :editor/ax.set-script-name
    (let [[new-name] args]
      {:uf/db (assoc state :panel/script-name new-name)})

    :editor/ax.set-script-match
    (let [[match] args]
      {:uf/db (assoc state :panel/script-match match)})

    :editor/ax.set-script-description
    (let [[desc] args]
      {:uf/db (assoc state :panel/script-description desc)})

    :editor/ax.update-scittle-status
    (let [[status] args]
      {:uf/db (assoc state :panel/scittle-status (or status :unknown))})

    :editor/ax.check-scittle
    {:uf/db (assoc state :panel/scittle-status :checking)
     :uf/fxs [[:editor/fx.check-scittle]]}

    :editor/ax.eval
    (let [code (:panel/code state)
          scittle-status (:panel/scittle-status state)]
      (cond
        ;; No code or already evaluating - no change
        (or (empty? code) (:panel/evaluating? state))
        nil

        ;; Scittle ready - eval directly
        (= :loaded scittle-status)
        {:uf/db (-> state
                    (assoc :panel/evaluating? true)
                    (update :panel/results conj {:type :input :text code}))
         :uf/fxs [[:editor/fx.eval-in-page code]]}

        ;; Scittle not ready - inject first
        :else
        {:uf/db (-> state
                    (assoc :panel/evaluating? true)
                    (assoc :panel/scittle-status :loading)
                    (update :panel/results conj {:type :input :text code}))
         :uf/fxs [[:editor/fx.inject-and-eval code]]}))

    :editor/ax.do-eval
    (let [[code] args]
      {:uf/fxs [[:editor/fx.eval-in-page code]]})

    :editor/ax.handle-eval-result
    (let [[result] args]
      {:uf/db (cond-> state
                :always (assoc :panel/evaluating? false)
                (:error result) (update :panel/results conj {:type :error :text (:error result)})
                (not (:error result)) (update :panel/results conj {:type :output :text (:result result)}))})

    :editor/ax.save-script
    (let [{:panel/keys [code script-name script-match script-description script-id original-name manifest-hints]} state]
      (if (or (empty? code) (empty? script-name))
        {:uf/db (assoc state :panel/save-status {:type :error :text "Name and code are required"})}
        (let [;; Normalize the display name for consistency
              normalized-name (script-utils/normalize-script-name script-name)
              ;; Check if name changed from original (means create new/copy, not update)
              name-changed? (and original-name (not= normalized-name original-name))
              ;; ID logic:
              ;; - No script-id (new script): generate new ID
              ;; - Name changed (fork/copy): generate new ID
              ;; - Name unchanged (update): preserve existing ID
              id (if (and script-id (not name-changed?))
                   script-id
                   (script-utils/generate-script-id))
              ;; Normalize match to vector (manifest allows string or vector)
              normalized-match (script-utils/normalize-match-patterns script-match)
              ;; Get require and run-at from manifest hints
              script-require (:require manifest-hints)
              script-run-at (:run-at manifest-hints)
              ;; Don't set :script/enabled here - let storage.cljs default it appropriately
              script (cond-> {:script/id id
                              :script/name normalized-name
                              :script/match normalized-match
                              :script/code code}
                       (seq script-description) (assoc :script/description script-description)
                       (seq script-require) (assoc :script/require script-require)
                       script-run-at (assoc :script/run-at script-run-at))
              ;; "Created" for new scripts OR when forking (name changed)
              ;; "Saved" only when updating existing script with same name
              action-text (if (or (not script-id) name-changed?) "Created" "Saved")]
          ;; State update happens in response handler - effect dispatches when background responds
          {:uf/fxs [[:editor/fx.save-script script normalized-name id action-text]]})))

    :editor/ax.handle-save-response
    (let [[{:keys [success error name is-update id action-text]}] args]
      (if success
        {:uf/db (assoc state
                       :panel/save-status {:type :success :text (str action-text " \"" name "\"")}
                       :panel/script-name name
                       :panel/original-name name
                       :panel/script-id id)
         :uf/fxs [[:uf/fx.defer-dispatch [[:db/ax.assoc :panel/save-status nil]] 2000]]}
        {:uf/db (assoc state :panel/save-status {:type :error :text (or error "Save failed")})}))

    :editor/ax.rename-script
    (let [{:panel/keys [script-name original-name]} state]
      (if (or (empty? script-name) (not original-name))
        {:uf/db (assoc state :panel/save-status {:type :error :text "Cannot rename: no script loaded"})}
        (let [normalized-name (script-utils/normalize-script-name script-name)]
          (if (= normalized-name original-name)
            {:uf/db (assoc state :panel/save-status {:type :error :text "Name unchanged"})}
            ;; State update happens in response handler
            {:uf/fxs [[:editor/fx.rename-script original-name normalized-name]]}))))

    :editor/ax.handle-rename-response
    (let [[{:keys [success error from-name to-name]}] args]
      (if success
        {:uf/db (assoc state
                       :panel/save-status {:type :success :text (str "Renamed to \"" to-name "\"")}
                       :panel/script-name to-name
                       :panel/original-name to-name)
         :uf/fxs [[:uf/fx.defer-dispatch [[:db/ax.assoc :panel/save-status nil]] 2000]]}
        {:uf/db (assoc state :panel/save-status {:type :error :text (or error "Rename failed")})}))

    :editor/ax.load-script-for-editing
    (let [[id name match code description] args
          ;; Parse manifest from loaded code for hint display
          manifest (try (mp/extract-manifest code) (catch :default _ nil))
          hints (build-manifest-hints manifest)]
      {:uf/db (assoc state
                     :panel/script-id id
                     :panel/script-name name
                     :panel/original-name name  ;; Track for rename detection
                     :panel/script-match match
                     :panel/code code
                     :panel/script-description (or description "")
                     :panel/manifest-hints hints)})

    :editor/ax.clear-results
    {:uf/db (assoc state :panel/results [])}

    :editor/ax.clear-code
    {:uf/db (assoc state :panel/code "")}

    :editor/ax.use-current-url
    {:uf/fxs [[:editor/fx.use-current-url [:db/ax.assoc :panel/script-match]]]}

    :editor/ax.check-editing-script
    {:uf/fxs [[:editor/fx.check-editing-script]]}

    :editor/ax.initialize-editor
    (let [[{:keys [code script-id original-name hostname]}] args
          ;; Use default script if no code saved
          effective-code (if (seq code) code default-script)
          ;; Parse manifest from code
          manifest (try (mp/extract-manifest effective-code) (catch :default _ nil))
          hints (build-manifest-hints manifest)
          dxs (build-manifest-dxs manifest)
          ;; Build new state - only set script-id/original-name if we have saved code
          new-state (cond-> (assoc state
                                   :panel/code effective-code
                                   :panel/manifest-hints hints
                                   :panel/current-hostname hostname)
                      ;; Only set these if restoring existing script (has saved code)
                      (seq code) (assoc :panel/script-id script-id
                                        :panel/original-name original-name))]
      (cond-> {:uf/db new-state}
        (seq dxs) (assoc :uf/dxs dxs)))

    :editor/ax.new-script
    ;; Reset editor to default script state, preserving results
    (let [manifest (try (mp/extract-manifest default-script) (catch :default _ nil))
          hints (build-manifest-hints manifest)
          dxs (build-manifest-dxs manifest)]
      {:uf/db (assoc state
                     :panel/code default-script
                     :panel/script-id nil
                     :panel/original-name nil
                     :panel/script-name ""
                     :panel/script-match ""
                     :panel/script-description ""
                     :panel/save-status nil
                     :panel/manifest-hints hints)
       :uf/fxs [[:editor/fx.clear-persisted-state]]
       :uf/dxs dxs})

    :editor/ax.set-selection
    (let [[selection] args]
      {:uf/db (assoc state :panel/selection selection)})

    :editor/ax.eval-selection
    (let [code-to-eval (get-code-to-eval state)
          scittle-status (:panel/scittle-status state)]
      (cond
        ;; No code or already evaluating - no change
        (or (empty? code-to-eval) (:panel/evaluating? state))
        nil

        ;; Scittle ready - eval directly
        (= :loaded scittle-status)
        {:uf/db (-> state
                    (assoc :panel/evaluating? true)
                    (update :panel/results conj {:type :input :text code-to-eval}))
         :uf/fxs [[:editor/fx.eval-in-page code-to-eval]]}

        ;; Scittle not ready - inject first
        :else
        {:uf/db (-> state
                    (assoc :panel/evaluating? true)
                    (assoc :panel/scittle-status :loading)
                    (update :panel/results conj {:type :input :text code-to-eval}))
         :uf/fxs [[:editor/fx.inject-and-eval code-to-eval]]}))

    :editor/ax.reload-script-from-storage
    (let [[script-name] args]
      {:uf/fxs [[:editor/fx.reload-script-from-storage script-name]]})

    :editor/ax.clear-system-banner
    (if (get-in state [:panel/system-banner :leaving])
      ;; Step 2: After animation, clear the banner
      {:uf/db (assoc state :panel/system-banner nil)}
      ;; Step 1: Mark as leaving, defer actual clear
      {:uf/db (assoc-in state [:panel/system-banner :leaving] true)
       :uf/fxs [[:uf/fx.defer-dispatch [[:editor/ax.clear-system-banner]] 250]]})

    :uf/unhandled-ax))
