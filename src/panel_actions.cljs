(ns panel-actions
  "Pure action handlers for the DevTools panel.
   No browser dependencies - testable without Chrome APIs."
  (:require [script-utils :as script-utils]
            [manifest-parser :as mp]))

(def default-script
  "Default script shown when panel initializes with no saved state.
   Has a valid manifest with proper name and namespace."
  "{:epupp/script-name \"hello_world.cljs\"
 :epupp/auto-run-match \"https://example.com/*\"
 :epupp/description \"A script saying hello\"}

(ns hello-world)

(defn hello [s]
  (js/console.log \"Epupp: Hello\" (str s \"!\")))

(hello \"World\")")

(defn- build-manifest-dxs
  "Build deferred dispatch actions from manifest data.
   Returns vector of actions to update name, match, description from manifest.

   CRITICAL: When manifest is present, it's the source of truth. If
   epupp/auto-run-match is absent from found-keys, we explicitly clear
   the match field to implement auto-run revocation."
  [manifest]
  (when manifest
    (let [script-name (get manifest "script-name")
          auto-run-match (get manifest "auto-run-match")
          description (get manifest "description")
          found-keys (get manifest "found-keys")
          has-auto-run-key? (some #(= % "epupp/auto-run-match") found-keys)]
      (cond-> []
        script-name (conj [:editor/ax.set-script-name script-name])
        ;; If manifest has auto-run-match key, use its value (including empty)
        ;; If manifest exists but no auto-run-match key, explicitly clear it
        has-auto-run-key? (conj [:editor/ax.set-script-match auto-run-match])
        (and (not has-auto-run-key?) manifest) (conj [:editor/ax.set-script-match nil])
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
     :inject (get manifest "inject")}))

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
         :uf/fxs [[:editor/fx.eval-in-page code (:inject (:panel/manifest-hints state))]]}

        ;; Scittle not ready - inject first
        :else
        {:uf/db (-> state
                    (assoc :panel/evaluating? true)
                    (assoc :panel/scittle-status :loading)
                    (update :panel/results conj {:type :input :text code}))
         :uf/fxs [[:editor/fx.inject-and-eval code (:require (:panel/manifest-hints state))]]}))

    :editor/ax.do-eval
    (let [[code] args]
      {:uf/fxs [[:editor/fx.eval-in-page code nil]]})

    :editor/ax.handle-eval-result
    (let [[result] args]
      {:uf/db (cond-> state
                :always (assoc :panel/evaluating? false)
                (:error result) (update :panel/results conj {:type :error :text (:error result)})
                (not (:error result)) (update :panel/results conj {:type :output :text (:result result)}))})

    :editor/ax.save-script
    (let [{:panel/keys [code script-name script-match script-description original-name manifest-hints]} state]
      (if (or (empty? code) (empty? script-name))
        {:uf/dxs [[:editor/ax.show-system-banner "error" "Name and code are required"]]}
        (let [;; Normalize the display name for consistency
              normalized-name (script-utils/normalize-script-name script-name)
              ;; Check if name changed from original (means create new/copy, not update)
              name-changed? (and original-name (not= normalized-name original-name))
              ;; Normalize match to vector (manifest allows string or vector)
              normalized-match (script-utils/normalize-match-patterns script-match)
              ;; Get require and run-at from manifest hints
              script-inject (:inject manifest-hints)
              script-run-at (:run-at manifest-hints)
              ;; Don't set :script/enabled here - let storage.cljs default it appropriately
              ;; Don't set :script/id here - let background generate/manage IDs
              script (cond-> {:script/name script-name
                              :script/match normalized-match
                              :script/code code}
                       (seq script-description) (assoc :script/description script-description)
                       (seq script-inject) (assoc :script/inject script-inject)
                       script-run-at (assoc :script/run-at script-run-at))
              ;; "Created" for new scripts OR when forking (name changed)
              ;; "Saved" only when updating existing script with same name
              action-text (if (or (not original-name) name-changed?) "Created" "Saved")]
          ;; State update happens in response handler - effect dispatches when background responds
          {:uf/fxs [[:editor/fx.save-script script normalized-name action-text]]})))

    :editor/ax.save-script-overwrite
    ;; Like save-script but with force flag to overwrite existing script
    (let [{:panel/keys [code script-name script-match script-description manifest-hints]} state]
      (if (or (empty? code) (empty? script-name))
        {:uf/dxs [[:editor/ax.show-system-banner "error" "Name and code are required"]]}
        (let [normalized-name (script-utils/normalize-script-name script-name)
              normalized-match (script-utils/normalize-match-patterns script-match)
              script-inject (:inject manifest-hints)
              script-run-at (:run-at manifest-hints)
              script (cond-> {:script/name script-name
                              :script/match normalized-match
                              :script/code code
                              :script/force? true}  ;; Force overwrite
                       (seq script-description) (assoc :script/description script-description)
                       (seq script-inject) (assoc :script/inject script-inject)
                       script-run-at (assoc :script/run-at script-run-at))]
          {:uf/fxs [[:editor/fx.save-script script normalized-name "Replaced"]]})))

    :editor/ax.handle-save-response
    (let [[{:keys [success error name action-text unchanged]}] args]
      (cond
        ;; Error case
        (not success)
        {:uf/dxs [[:editor/ax.show-system-banner "error" (or error "Save failed")]]}

        ;; Unchanged - show info banner
        unchanged
        {:uf/db (assoc state
                       :panel/script-name name
                       :panel/original-name name)
         :uf/dxs [[:editor/ax.show-system-banner "info" (str "Script \"" name "\" unchanged")]]}

        ;; Success - show success banner
        :else
        {:uf/db (assoc state
                       :panel/script-name name
                       :panel/original-name name)
         :uf/dxs [[:editor/ax.show-system-banner "success" (str action-text " \"" name "\"")]]}))

    :editor/ax.rename-script
    (if-let [original-name (:panel/original-name state)]
      (let [new-name (:panel/script-name state)]
        (if (= new-name original-name)
          {:uf/dxs [[:editor/ax.show-system-banner "error" "Name unchanged"]]}
          ;; State update happens in response handler
          {:uf/fxs [[:editor/fx.rename-script original-name new-name]]}))
      {:uf/dxs [[:editor/ax.show-system-banner "error" "Cannot rename: no script loaded"]]})

    :editor/ax.handle-rename-response
    (let [[{:keys [success error to-name]}] args]
      (if success
        {:uf/db (-> state
                    (assoc :panel/original-name to-name)
                    (assoc :panel/script-name to-name))
         :uf/fxs [[:editor/fx.persist-code (:panel/code state)]]
         :uf/dxs [[:editor/ax.show-system-banner "success" (str "Renamed to \"" to-name "\"")]]}
        {:uf/dxs [[:editor/ax.show-system-banner "error" (or error "Rename failed")]]}))

    :editor/ax.load-script-for-editing
    (let [[name match code description] args
          ;; Parse manifest from loaded code for hint display
          manifest (try (mp/extract-manifest code) (catch :default _ nil))
          hints (build-manifest-hints manifest)]
      {:uf/db (assoc state
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
    (let [[{:keys [code original-name hostname]}] args
          ;; Use default script if no code saved
          effective-code (if (seq code) code default-script)
          ;; Parse manifest from code
          manifest (try (mp/extract-manifest effective-code) (catch :default _ nil))
          hints (build-manifest-hints manifest)
          dxs (build-manifest-dxs manifest)
          ;; Build new state - only set original-name if we have saved code
          new-state (cond-> (assoc state
                                   :panel/code effective-code
                                   :panel/manifest-hints hints
                                   :panel/current-hostname hostname)
                      ;; Only set original-name if restoring existing script (has saved code)
                      (seq code) (assoc :panel/original-name original-name))]
      (cond-> {:uf/db new-state}
        (seq dxs) (assoc :uf/dxs dxs)))

    :editor/ax.new-script
    ;; Reset editor to default script state, preserving results
    (let [manifest (try (mp/extract-manifest default-script) (catch :default _ nil))
          hints (build-manifest-hints manifest)
          dxs (build-manifest-dxs manifest)]
      {:uf/db (assoc state
                     :panel/code default-script
                     :panel/original-name nil
                     :panel/script-name ""
                     :panel/script-match ""
                     :panel/script-description ""
                     :panel/manifest-hints hints)
       :uf/fxs [[:editor/fx.clear-persisted-state (:panel/current-hostname state)]]
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
         :uf/fxs [[:editor/fx.eval-in-page code-to-eval (:require (:panel/manifest-hints state))]]}

        ;; Scittle not ready - inject first
        :else
        {:uf/db (-> state
                    (assoc :panel/evaluating? true)
                    (assoc :panel/scittle-status :loading)
                    (update :panel/results conj {:type :input :text code-to-eval}))
         :uf/fxs [[:editor/fx.inject-and-eval code-to-eval (:inject (:panel/manifest-hints state))]]}))

    :editor/ax.reload-script-from-storage
    (let [[script-name] args]
      {:uf/fxs [[:editor/fx.reload-script-from-storage script-name]]})

    ;; System banner actions - multi-message support (matches popup pattern)
    ;; Optional 3rd arg :category - banners with same category replace each other
    :editor/ax.show-system-banner
    (let [[event-type message category] args
          now (or (:system/now uf-data) (.now js/Date))
          banner-id (str "msg-" now "-" (count (:panel/system-banners state)))
          new-banner (cond-> {:id banner-id :type event-type :message message}
                       category (assoc :category category))
          banners (or (:panel/system-banners state) [])
          ;; If category provided, filter out existing banners with same category
          banners (if category
                    (filterv #(not= (:category %) category) banners)
                    banners)]
      {:uf/db (assoc state :panel/system-banners (conj banners new-banner))
       :uf/fxs [[:uf/fx.defer-dispatch [[:editor/ax.clear-system-banner banner-id]] 2000]]})

    :editor/ax.clear-system-banner
    (let [[banner-id] args
          banners (or (:panel/system-banners state) [])
          target-banner (some #(when (= (:id %) banner-id) %) banners)]
      (if (and target-banner (:leaving target-banner))
        ;; Step 2: After animation, remove the banner
        {:uf/db (assoc state :panel/system-banners (filterv #(not= (:id %) banner-id) banners))}
        ;; Step 1: Mark as leaving, defer actual removal
        {:uf/db (assoc state :panel/system-banners
                       (mapv #(if (= (:id %) banner-id)
                                (assoc % :leaving true)
                                %)
                             banners))
         :uf/fxs [[:uf/fx.defer-dispatch [[:editor/ax.clear-system-banner banner-id]] 250]]}))
    :editor/ax.set-needs-refresh
    {:uf/db (assoc state :panel/needs-refresh? true)}

    :editor/ax.reset-for-navigation
    {:uf/db (assoc state
                   :panel/evaluating? false
                   :panel/scittle-status :unknown)}

    :editor/ax.set-init-version
    (let [[version] args]
      {:uf/db (assoc state :panel/init-version version)})

    :editor/ax.track-bulk-name
    (let [[bulk-id script-name] args]
      {:uf/db (update-in state [:panel/system-bulk-names bulk-id] (fnil conj []) script-name)})

    :editor/ax.clear-bulk-names
    (let [[bulk-id] args]
      {:uf/db (update state :panel/system-bulk-names dissoc bulk-id)})

    :editor/ax.update-scripts-list
    (let [[scripts] args]
      {:uf/db (assoc state :panel/scripts-list (or scripts []))})

    :uf/unhandled-ax))
