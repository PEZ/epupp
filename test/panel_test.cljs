(ns panel-test
  "Tests for panel action handlers - pure state transitions"
  (:require ["vitest" :refer [describe test expect]]
            [panel-actions :as panel-actions]))

;; ============================================================
;; Panel Action Tests
;; ============================================================

(def initial-state
  {:panel/results []
   :panel/code ""
   :panel/evaluating? false
   :panel/scittle-status :unknown
   :panel/script-name ""
   :panel/script-match ""
   :panel/script-description ""
   :panel/system-banners []})

(def uf-data {:system/now 1234567890})

;; ============================================================
;; Panel handle-action tests
;; ============================================================

(defn- test_set_code_updates_code []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code "new code"])]
    (-> (expect (:panel/code (:uf/db result)))
        (.toBe "new code"))))

(defn- test_set_script_name_updates_name []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-name "My Script"])]
    (-> (expect (:panel/script-name (:uf/db result)))
        (.toBe "My Script"))))

(defn- test_set_script_match_updates_match_pattern []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-match "*://github.com/*"])]
    (-> (expect (:panel/script-match (:uf/db result)))
        (.toBe "*://github.com/*"))))

(defn- test_set_script_description_updates_description []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-description "A helpful description"])]
    (-> (expect (:panel/script-description (:uf/db result)))
        (.toBe "A helpful description"))))

(defn- test_update_scittle_status_updates_status []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.update-scittle-status :loaded])]
    (-> (expect (:panel/scittle-status (:uf/db result)))
        (.toBe :loaded))))

(defn- test_clear_results_empties_results []
  (let [state-with-results (assoc initial-state :panel/results [{:type :input :text "code"}])
        result (panel-actions/handle-action state-with-results uf-data [:editor/ax.clear-results])]
    (-> (expect (:panel/results (:uf/db result)))
        (.toEqual []))))

(defn- test_clear_code_empties_code []
  (let [state-with-code (assoc initial-state :panel/code "(+ 1 2)")
        result (panel-actions/handle-action state-with-code uf-data [:editor/ax.clear-code])]
    (-> (expect (:panel/code (:uf/db result)))
        (.toBe ""))))

(defn- test_handle_eval_result_adds_output_to_results []
  (let [state-evaluating (assoc initial-state :panel/evaluating? true)
        result (panel-actions/handle-action state-evaluating uf-data [:editor/ax.handle-eval-result {:result "42"}])
        new-state (:uf/db result)]
    (-> (expect (:panel/evaluating? new-state))
        (.toBe false))
    (-> (expect (count (:panel/results new-state)))
        (.toBe 1))
    (-> (expect (:type (first (:panel/results new-state))))
        (.toBe :output))))

(defn- test_handle_eval_result_adds_error_to_results []
  (let [state-evaluating (assoc initial-state :panel/evaluating? true)
        result (panel-actions/handle-action state-evaluating uf-data [:editor/ax.handle-eval-result {:error "oops"}])
        new-state (:uf/db result)]
    (-> (expect (:panel/evaluating? new-state))
        (.toBe false))
    (-> (expect (:type (first (:panel/results new-state))))
        (.toBe :error))))

(defn- test_load_script_for_editing_populates_all_fields []
  (let [result (panel-actions/handle-action initial-state uf-data
                                            [:editor/ax.load-script-for-editing
                                             "Test Script"
                                             "*://example.com/*"
                                             "(println \"hello\")"
                                             "A description"])
        new-state (:uf/db result)]
    (-> (expect (:panel/script-name new-state))
        (.toBe "Test Script"))
    (-> (expect (:panel/script-match new-state))
        (.toBe "*://example.com/*"))
    (-> (expect (:panel/code new-state))
        (.toBe "(println \"hello\")"))
    (-> (expect (:panel/script-description new-state))
        (.toBe "A description"))))

(defn- test_load_script_for_editing_handles_missing_description []
  (let [result (panel-actions/handle-action initial-state uf-data
                                            [:editor/ax.load-script-for-editing
                                             "Test Script"
                                             "*://example.com/*"
                                             "(println \"hello\")"])
        new-state (:uf/db result)]
    ;; Missing description should default to empty string
    (-> (expect (:panel/script-description new-state))
        (.toBe ""))))

(describe "panel handle-action"
          (fn []
            (test ":editor/ax.set-code updates code" test_set_code_updates_code)
            (test ":editor/ax.set-script-name updates name" test_set_script_name_updates_name)
            (test ":editor/ax.set-script-match updates match pattern" test_set_script_match_updates_match_pattern)
            (test ":editor/ax.set-script-description updates description" test_set_script_description_updates_description)
            (test ":editor/ax.update-scittle-status updates status" test_update_scittle_status_updates_status)
            (test ":editor/ax.clear-results empties results" test_clear_results_empties_results)
            (test ":editor/ax.clear-code empties code" test_clear_code_empties_code)
            (test ":editor/ax.handle-eval-result adds output to results" test_handle_eval_result_adds_output_to_results)
            (test ":editor/ax.handle-eval-result adds error to results" test_handle_eval_result_adds_error_to_results)
            (test ":editor/ax.load-script-for-editing populates all fields" test_load_script_for_editing_populates_all_fields)
            (test ":editor/ax.load-script-for-editing handles missing description" test_load_script_for_editing_handles_missing_description)))

;; ============================================================
;; Panel eval action tests
;; ============================================================

(defn- test_eval_with_empty_code_returns_nil []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.eval])]
    (-> (expect result)
        (.toBeNull))))

(defn- test_eval_when_already_evaluating_returns_nil []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/evaluating? true))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval])]
    (-> (expect result)
        (.toBeNull))))

(defn- test_eval_with_loaded_scittle_triggers_direct_eval []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/scittle-status :loaded))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval])
        new-state (:uf/db result)]
    (-> (expect (:panel/evaluating? new-state))
        (.toBe true))
    (-> (expect (count (:panel/results new-state)))
        (.toBe 1))
    ;; Should trigger fx.eval-in-page
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :editor/fx.eval-in-page))))

(defn- test_eval_without_scittle_triggers_inject_and_eval []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/scittle-status :unknown))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval])
        new-state (:uf/db result)]
    (-> (expect (:panel/evaluating? new-state))
        (.toBe true))
    (-> (expect (:panel/scittle-status new-state))
        (.toBe :loading))
    ;; Should trigger fx.inject-and-eval
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :editor/fx.inject-and-eval))))

(describe "panel eval action"
          (fn []
            (test ":editor/ax.eval with empty code returns nil" test_eval_with_empty_code_returns_nil)
            (test ":editor/ax.eval when already evaluating returns nil" test_eval_when_already_evaluating_returns_nil)
            (test ":editor/ax.eval with loaded scittle triggers direct eval" test_eval_with_loaded_scittle_triggers_direct_eval)
            (test ":editor/ax.eval without scittle triggers inject-and-eval" test_eval_without_scittle_triggers_inject_and_eval)))

;; ============================================================
;; Panel save action tests
;; ============================================================

(defn- test_save_script_with_missing_fields_shows_error []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.save-script])
        dxs (:uf/dxs result)
        [action-type event-type _message] (first dxs)]
    ;; Should dispatch show-system-banner with error
    (-> (expect action-type)
        (.toBe :editor/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "error"))))

(defn- test_save_script_with_complete_fields_triggers_save_effect []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match "*://example.com/*"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])]
    ;; Should NOT update state directly (async response will do that)
    (-> (expect (:uf/db result))
        (.toBeUndefined))
    ;; Should trigger save effect with script and normalized name
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :editor/fx.save-script))
    ;; Effect args: [script normalized-name]
    (let [[_fx script name] (first (:uf/fxs result))]
      (-> (expect (:script/name script))
          (.toBe "my_script.cljs"))
      (-> (expect name)
          (.toBe "my_script.cljs")))))

(defn- test_save_script_preserves_name_when_editing_with_unchanged_name []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "my_script.cljs")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/original-name "my_script.cljs"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script normalized-name] (first (:uf/fxs result))]
    ;; Should preserve name when unchanged
    (-> (expect (:script/name script))
        (.toBe "my_script.cljs"))
    (-> (expect normalized-name)
        (.toBe "my_script.cljs"))))

(defn- test_save_script_uses_new_name_when_name_changed []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "New Name")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/original-name "old_name.cljs"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script normalized-name] (first (:uf/fxs result))]
    ;; Name should be normalized to new name
    (-> (expect (:script/name script))
        (.toBe "new_name.cljs"))
    (-> (expect normalized-name)
        (.toBe "new_name.cljs"))))

(defn- test_save_script_normalizes_name_for_new_scripts []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Cool Script")
                  (assoc :panel/script-match "*://example.com/*"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script normalized-name] (first (:uf/fxs result))]
    ;; Name is normalized for display consistency
    (-> (expect (:script/name script))
        (.toBe "my_cool_script.cljs"))
    (-> (expect normalized-name)
        (.toBe "my_cool_script.cljs"))))

(defn- test_save_script_includes_description_when_provided []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/script-description "A helpful description"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script] (first (:uf/fxs result))]
    (-> (expect (:script/description script))
        (.toBe "A helpful description"))))

(defn- test_save_script_omits_description_when_empty []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/script-description ""))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script] (first (:uf/fxs result))]
    ;; Empty description should not be included in script
    (-> (expect (:script/description script))
        (.toBeUndefined))))

(defn- test_save_script_includes_description_in_effect_when_set []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/script-description "A description"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script _name _id _action-text] (first (:uf/fxs result))]
    ;; Description should be in the script sent to background
    (-> (expect (:script/description script))
        (.toBe "A description"))))

(defn- test_save_script_preserves_vector_match_without_double_wrapping []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match ["*://example.com/*" "*://foo.com/*"]))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script] (first (:uf/fxs result))]
    ;; Vector match should stay flat (not double-wrapped)
    (-> (expect (js/Array.isArray (:script/match script)))
        (.toBe true))
    (-> (expect (count (:script/match script)))
        (.toBe 2))
    (-> (expect (aget (:script/match script) 0))
        (.toBe "*://example.com/*"))
    (-> (expect (aget (:script/match script) 1))
        (.toBe "*://foo.com/*"))))

(defn- test_save_script_includes_inject_from_manifest_hints []
  (let [state (-> initial-state
                  (assoc :panel/code "(ns test)")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/manifest-hints {:inject ["scittle://reagent.js"]}))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script] (first (:uf/fxs result))]
    (-> (expect (:script/inject script))
        (.toEqual ["scittle://reagent.js"]))))

(defn- test_save_script_succeeds_without_site_match []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match ""))  ;; No site-match
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])]
    ;; Should NOT show error - save should proceed
    (-> (expect (:uf/db result))
        (.toBeUndefined))
    ;; Should trigger save effect
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :editor/fx.save-script))
    ;; Script should have empty match vector
    (let [[_fx script] (first (:uf/fxs result))]
      (-> (expect (:script/match script))
          (.toEqual [])))))

(defn- test_save_script_without_site_match_uses_empty_vector []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match nil))  ;; nil site-match
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx script] (first (:uf/fxs result))]
    ;; nil should normalize to empty vector
    (-> (expect (:script/match script))
        (.toEqual []))))

(describe "panel save action"
          (fn []
            (test ":editor/ax.save-script with missing fields shows error" test_save_script_with_missing_fields_shows_error)
            (test ":editor/ax.save-script succeeds without site-match" test_save_script_succeeds_without_site_match)
            (test ":editor/ax.save-script without site-match uses empty vector" test_save_script_without_site_match_uses_empty_vector)
            (test ":editor/ax.save-script with complete fields triggers save effect" test_save_script_with_complete_fields_triggers_save_effect)
            (test ":editor/ax.save-script preserves name when editing with unchanged name" test_save_script_preserves_name_when_editing_with_unchanged_name)
            (test ":editor/ax.save-script uses new name when name changed" test_save_script_uses_new_name_when_name_changed)
            (test ":editor/ax.save-script normalizes name for new scripts" test_save_script_normalizes_name_for_new_scripts)
            (test ":editor/ax.save-script includes description when provided" test_save_script_includes_description_when_provided)
            (test ":editor/ax.save-script omits description when empty" test_save_script_omits_description_when_empty)
            (test ":editor/ax.save-script includes description in effect when set" test_save_script_includes_description_in_effect_when_set)
            (test ":editor/ax.save-script preserves vector match without double-wrapping" test_save_script_preserves_vector_match_without_double_wrapping)
            (test ":editor/ax.save-script includes inject from manifest hints" test_save_script_includes_inject_from_manifest_hints)))

;; ============================================================
;; Panel save response handling tests
;; ============================================================

(defn- test_handle_save_response_updates_state_on_success []
  (let [result (panel-actions/handle-action initial-state uf-data
                                            [:editor/ax.handle-save-response
                                             {:success true
                                              :name "my_script.cljs"
                                              :id "script-123"
                                              :action-text "Created"}])
        new-state (:uf/db result)
        dxs (:uf/dxs result)
        [action-type event-type message] (first dxs)]
    ;; Should dispatch show-system-banner with success
    (-> (expect action-type)
        (.toBe :editor/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "success"))
    (-> (expect message)
        (.toContain "Created"))
    ;; State updates
    (-> (expect (:panel/script-name new-state))
        (.toBe "my_script.cljs"))
    (-> (expect (:panel/original-name new-state))
        (.toBe "my_script.cljs"))))

(defn- test_handle_save_response_shows_error_on_failure []
  (let [result (panel-actions/handle-action initial-state uf-data
                                            [:editor/ax.handle-save-response
                                             {:success false
                                              :error "Name collision"}])
        dxs (:uf/dxs result)
        [action-type event-type message] (first dxs)]
    ;; Should dispatch show-system-banner with error
    (-> (expect action-type)
        (.toBe :editor/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "error"))
    (-> (expect message)
        (.toBe "Name collision"))
    ;; Should not have db changes on error-only response
    (-> (expect (:uf/db result))
        (.toBeUndefined))))

(defn- test_handle_rename_response_updates_state_on_success []
  (let [state (assoc initial-state :panel/original-name "old_name.cljs")
        result (panel-actions/handle-action state uf-data
                                            [:editor/ax.handle-rename-response
                                             {:success true
                                              :to-name "new_name.cljs"}])
        new-state (:uf/db result)
        dxs (:uf/dxs result)
        [action-type event-type message] (first dxs)]
    ;; Should dispatch show-system-banner with success
    (-> (expect action-type)
        (.toBe :editor/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "success"))
    (-> (expect message)
        (.toContain "Renamed"))
    ;; State updates
    (-> (expect (:panel/script-name new-state))
        (.toBe "new_name.cljs"))
    (-> (expect (:panel/original-name new-state))
        (.toBe "new_name.cljs"))))

(defn- test_handle_rename_response_shows_error_on_failure []
  (let [result (panel-actions/handle-action initial-state uf-data
                                            [:editor/ax.handle-rename-response
                                             {:success false
                                              :error "Script not found"}])
        dxs (:uf/dxs result)
        [action-type event-type message] (first dxs)]
    ;; Should dispatch show-system-banner with error
    (-> (expect action-type)
        (.toBe :editor/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "error"))
    (-> (expect message)
        (.toBe "Script not found"))))

(describe "panel save response handling"
          (fn []
            (test ":editor/ax.handle-save-response updates state on success" test_handle_save_response_updates_state_on_success)
            (test ":editor/ax.handle-save-response shows error on failure" test_handle_save_response_shows_error_on_failure)
            (test ":editor/ax.handle-rename-response updates state on success" test_handle_rename_response_updates_state_on_success)
            (test ":editor/ax.handle-rename-response shows error on failure" test_handle_rename_response_shows_error_on_failure)))

;; ============================================================
;; Phase 2: Manifest-driven metadata tests
;; ============================================================

(defn- test_set_code_parses_manifest_and_returns_dxs []
  (let [code "^{:epupp/script-name \"GitHub Tweaks\"
  :epupp/auto-run-match \"https://github.com/*\"
  :epupp/description \"Enhance GitHub UX\"}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)
        dxs (:uf/dxs result)]
    ;; Code should be updated
    (-> (expect (:panel/code new-state))
        (.toBe code))
    ;; Should have dxs to update fields from manifest
    (-> (expect dxs)
        (.toBeTruthy))
    ;; dxs should contain set-script-name with normalized name
    (-> (expect (some #(= (first %) :editor/ax.set-script-name) dxs))
        (.toBeTruthy))))

(defn- test_set_code_stores_manifest_hints_for_normalization []
  (let [code "^{:epupp/script-name \"GitHub Tweaks\"}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)]
    ;; Should store manifest hints showing normalization occurred
    (-> (expect (:panel/manifest-hints new-state))
        (.toBeTruthy))
    (-> (expect (:name-normalized? (:panel/manifest-hints new-state)))
        (.toBe true))
    (-> (expect (:raw-script-name (:panel/manifest-hints new-state)))
        (.toBe "GitHub Tweaks"))))

(defn- test_set_code_stores_unknown_keys_in_hints []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/author \"PEZ\"
  :epupp/version \"1.0\"}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)]
    (-> (expect (:unknown-keys (:panel/manifest-hints new-state)))
        (.toContain "epupp/author"))
    (-> (expect (:unknown-keys (:panel/manifest-hints new-state)))
        (.toContain "epupp/version"))))

(defn- test_set_code_clears_hints_when_no_manifest []
  (let [state-with-hints (assoc initial-state
                                :panel/manifest-hints {:name-normalized? true})
        code "(defn foo [] 42)"
        result (panel-actions/handle-action state-with-hints uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)]
    ;; Should clear hints when no manifest found
    (-> (expect (:panel/manifest-hints new-state))
        (.toBeFalsy))))

(defn- test_set_code_handles_site_match_as_vector []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match [\"https://github.com/*\" \"https://gist.github.com/*\"]}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        dxs (:uf/dxs result)
        ;; Find the set-script-match action
        match-action (first (filter #(= (first %) :editor/ax.set-script-match) dxs))]
    ;; Should pass vector to set-script-match
    (-> (expect (second match-action))
        (.toEqual ["https://github.com/*" "https://gist.github.com/*"]))))

(defn- test_set_code_stores_run_at_invalid_flag_in_hints []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/run-at \"invalid-timing\"}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)]
    (-> (expect (:run-at-invalid? (:panel/manifest-hints new-state)))
        (.toBe true))
    (-> (expect (:raw-run-at (:panel/manifest-hints new-state)))
        (.toBe "invalid-timing"))))

(defn- test_set_code_stores_inject_in_manifest_hints []
  (let [code "{:epupp/script-name \"test.cljs\"
  :epupp/inject [\"scittle://reagent.js\" \"scittle://pprint.js\"]}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)]
    ;; Should store inject in hints
    (-> (expect (:inject (:panel/manifest-hints new-state)))
        (.toEqual ["scittle://reagent.js" "scittle://pprint.js"]))))

(defn- test_set_code_stores_empty_inject_when_missing []
  (let [code "{:epupp/script-name \"test.cljs\"}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)]
    ;; Should store empty vector when inject is missing
    (-> (expect (:inject (:panel/manifest-hints new-state)))
        (.toEqual []))))

(describe "panel set-code with manifest parsing"
          (fn []
            (test ":editor/ax.set-code parses manifest and returns dxs to update fields" test_set_code_parses_manifest_and_returns_dxs)
            (test ":editor/ax.set-code stores manifest hints for normalization" test_set_code_stores_manifest_hints_for_normalization)
            (test ":editor/ax.set-code stores unknown keys in hints" test_set_code_stores_unknown_keys_in_hints)
            (test ":editor/ax.set-code clears hints when no manifest" test_set_code_clears_hints_when_no_manifest)
            (test ":editor/ax.set-code handles site-match as vector" test_set_code_handles_site_match_as_vector)
            (test ":editor/ax.set-code stores run-at invalid flag in hints" test_set_code_stores_run_at_invalid_flag_in_hints)
            (test ":editor/ax.set-code stores inject in manifest hints" test_set_code_stores_inject_in_manifest_hints)
            (test ":editor/ax.set-code stores empty inject when missing" test_set_code_stores_empty_inject_when_missing)))

;; ============================================================
;; Panel initialization tests
;; ============================================================

(defn- test_initialize_editor_with_saved_code_parses_manifest []
  (let [saved-code "{:epupp/script-name \"My Script\"
 :epupp/auto-run-match \"https://example.com/*\"
 :epupp/description \"Test script\"}

(ns my-script)
(println \"hello\")"
        result (panel-actions/handle-action
                initial-state uf-data
                [:editor/ax.initialize-editor
                 {:code saved-code
                  :original-name "my_script.cljs"}])
        new-state (:uf/db result)
        dxs (:uf/dxs result)]
    ;; Code should be set
    (-> (expect (:panel/code new-state))
        (.toBe saved-code))
    ;; Original name should be set
    (-> (expect (:panel/original-name new-state))
        (.toBe "my_script.cljs"))
    ;; Manifest hints should be populated
    (-> (expect (:panel/manifest-hints new-state))
        (.toBeTruthy))
    ;; Should have dxs to set name/match/description from manifest
    (-> (expect (some #(= (first %) :editor/ax.set-script-name) dxs))
        (.toBeTruthy))
    (-> (expect (some #(= (first %) :editor/ax.set-script-match) dxs))
        (.toBeTruthy))
    (-> (expect (some #(= (first %) :editor/ax.set-script-description) dxs))
        (.toBeTruthy))))

(defn- test_initialize_editor_with_no_saved_code_uses_default_script []
  (let [result (panel-actions/handle-action
                initial-state uf-data
                [:editor/ax.initialize-editor {}])
        new-state (:uf/db result)
        dxs (:uf/dxs result)]
    ;; Code should have default script
    (-> (expect (:panel/code new-state))
        (.toContain "hello_world.cljs"))
    (-> (expect (:panel/code new-state))
        (.toContain "(ns hello-world)"))
    ;; Should NOT have script-id (it's a new script template)
    (-> (expect (:panel/script-id new-state))
        (.toBeFalsy))
    ;; Should have dxs to set name from manifest
    (-> (expect (some #(= (first %) :editor/ax.set-script-name) dxs))
        (.toBeTruthy))))

(defn- test_initialize_editor_with_empty_code_uses_default_script []
  (let [result (panel-actions/handle-action
                initial-state uf-data
                [:editor/ax.initialize-editor {:code ""}])
        new-state (:uf/db result)]
    ;; Empty code should trigger default script
    (-> (expect (:panel/code new-state))
        (.toContain "hello_world.cljs"))))

(describe "panel initialization action"
          (fn []
            (test ":editor/ax.initialize-editor with saved code parses manifest" test_initialize_editor_with_saved_code_parses_manifest)
            (test ":editor/ax.initialize-editor with no saved code uses default script" test_initialize_editor_with_no_saved_code_uses_default_script)
            (test ":editor/ax.initialize-editor with empty code uses default script" test_initialize_editor_with_empty_code_uses_default_script)))

;; ============================================================
;; New Script action tests
;; ============================================================

(defn- test_new_script_resets_to_default_script []
  (let [state-with-script (-> initial-state
                              (assoc :panel/code "(println \"custom code\")")
                              (assoc :panel/script-name "custom_script.cljs")
                              (assoc :panel/script-match "*://custom.com/*")
                              (assoc :panel/script-description "Custom description")
                              (assoc :panel/original-name "custom_script.cljs")
                              (assoc :panel/system-banners [{:id "test" :type "success" :message "Saved"}]))
        result (panel-actions/handle-action state-with-script uf-data [:editor/ax.new-script])
        new-state (:uf/db result)]
    ;; Code should be reset to default script
    (-> (expect (:panel/code new-state))
        (.toContain "hello_world.cljs"))
    (-> (expect (:panel/code new-state))
        (.toContain "(ns hello-world)"))
    ;; Original name should be cleared
    (-> (expect (:panel/original-name new-state))
        (.toBeNull))))

(defn- test_new_script_clears_persisted_state []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.new-script])
        fxs (:uf/fxs result)]
    ;; Should trigger clear-persisted-state effect
    (-> (expect (some #(= (first %) :editor/fx.clear-persisted-state) fxs))
        (.toBeTruthy))))

(defn- test_new_script_returns_dxs_to_parse_default_manifest []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.new-script])
        dxs (:uf/dxs result)]
    ;; Should have dxs to set script name from default manifest
    ;; Note: No set-script-match expected - default script has no site-match (manual-only)
    (-> (expect (some #(= (first %) :editor/ax.set-script-name) dxs))
        (.toBeTruthy))))

(defn- test_new_script_preserves_results_array []
  (let [state-with-results (-> initial-state
                               (assoc :panel/results [{:type :input :text "(+ 1 2)"}
                                                      {:type :output :text "3"}]))
        result (panel-actions/handle-action state-with-results uf-data [:editor/ax.new-script])
        new-state (:uf/db result)]
    ;; Results should be preserved
    (-> (expect (count (:panel/results new-state)))
        (.toBe 2))
    (-> (expect (:type (first (:panel/results new-state))))
        (.toBe :input))))

(describe "panel new script action"
          (fn []
            (test ":editor/ax.new-script resets to default script" test_new_script_resets_to_default_script)
            (test ":editor/ax.new-script clears persisted state" test_new_script_clears_persisted_state)
            (test ":editor/ax.new-script returns dxs to parse default manifest" test_new_script_returns_dxs_to_parse_default_manifest)
            (test ":editor/ax.new-script preserves results array" test_new_script_preserves_results_array)))

;; ============================================================
;; Panel selection action tests
;; ============================================================

(defn- test_set_selection_updates_selection_state []
  (let [selection {:start 0 :end 7 :text "(+ 1 2)"}
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-selection selection])]
    (-> (expect (:panel/selection (:uf/db result)))
        (.toEqual {:start 0 :end 7 :text "(+ 1 2)"}))))

(defn- test_set_selection_clears_selection_with_nil []
  (let [state-with-selection (assoc initial-state :panel/selection {:start 0 :end 5 :text "hello"})
        result (panel-actions/handle-action state-with-selection uf-data [:editor/ax.set-selection nil])]
    (-> (expect (:panel/selection (:uf/db result)))
        (.toBeNull))))

(defn- test_eval_selection_with_selection_evaluates_selected_text []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)\n(* 3 4)")
                  (assoc :panel/selection {:start 8 :end 15 :text "(* 3 4)"})
                  (assoc :panel/scittle-status :loaded))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
        new-state (:uf/db result)
        [effect-name effect-code] (first (:uf/fxs result))]
    ;; Should be evaluating
    (-> (expect (:panel/evaluating? new-state))
        (.toBe true))
    ;; Should show selection as input, not full code
    (-> (expect (:text (last (:panel/results new-state))))
        (.toBe "(* 3 4)"))
    ;; Effect should receive selection text
    (-> (expect effect-name)
        (.toBe :editor/fx.eval-in-page))
    (-> (expect effect-code)
        (.toBe "(* 3 4)"))))

(defn- test_eval_selection_without_selection_evaluates_full_code []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/selection nil)
                  (assoc :panel/scittle-status :loaded))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
        [_effect-name effect-code] (first (:uf/fxs result))]
    ;; Should fall back to full code
    (-> (expect effect-code)
        (.toBe "(+ 1 2)"))))

(defn- test_eval_selection_with_empty_selection_text_evaluates_full_code []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/selection {:start 3 :end 3 :text ""})
                  (assoc :panel/scittle-status :loaded))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
        [_effect-name effect-code] (first (:uf/fxs result))]
    ;; Empty selection (cursor position) falls back to full code
    (-> (expect effect-code)
        (.toBe "(+ 1 2)"))))

(defn- test_eval_selection_with_empty_code_and_empty_selection_returns_nil []
  (let [state (-> initial-state
                  (assoc :panel/selection {:start 0 :end 0 :text ""}))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])]
    ;; Should return nil when both code and selection are empty
    (-> (expect result)
        (.toBeNull))))

(defn- test_eval_selection_when_already_evaluating_returns_nil []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/selection {:start 0 :end 7 :text "(+ 1 2)"})
                  (assoc :panel/evaluating? true))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])]
    (-> (expect result)
        (.toBeNull))))

(defn- test_eval_selection_without_scittle_triggers_inject_and_eval []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)\n(* 3 4)")
                  (assoc :panel/selection {:start 8 :end 15 :text "(* 3 4)"})
                  (assoc :panel/scittle-status :unknown))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
        new-state (:uf/db result)
        [effect-name effect-code] (first (:uf/fxs result))]
    ;; Should trigger inject-and-eval with selection
    (-> (expect effect-name)
        (.toBe :editor/fx.inject-and-eval))
    (-> (expect effect-code)
        (.toBe "(* 3 4)"))
    ;; Status should be loading
    (-> (expect (:panel/scittle-status new-state))
        (.toBe :loading))))

(describe "panel selection actions"
          (fn []
            (test ":editor/ax.set-selection updates selection state" test_set_selection_updates_selection_state)
            (test ":editor/ax.set-selection clears selection with nil" test_set_selection_clears_selection_with_nil)
            (test ":editor/ax.eval-selection with selection evaluates selected text" test_eval_selection_with_selection_evaluates_selected_text)
            (test ":editor/ax.eval-selection without selection evaluates full code" test_eval_selection_without_selection_evaluates_full_code)
            (test ":editor/ax.eval-selection with empty selection text evaluates full code" test_eval_selection_with_empty_selection_text_evaluates_full_code)
            (test ":editor/ax.eval-selection with empty code and empty selection returns nil" test_eval_selection_with_empty_code_and_empty_selection_returns_nil)
            (test ":editor/ax.eval-selection when already evaluating returns nil" test_eval_selection_when_already_evaluating_returns_nil)
            (test ":editor/ax.eval-selection without scittle triggers inject-and-eval" test_eval_selection_without_scittle_triggers_inject_and_eval)))

;; === Panel system banner category tests ===

(defn- test_show_system_banner_with_category_replaces_existing_banner []
  (let [existing-banner {:id "msg-1" :type "info" :message "Processing..." :category "operation"}
        state {:panel/system-banners [existing-banner]}
        result (panel-actions/handle-action state uf-data [:editor/ax.show-system-banner "success" "Done!" "operation"])
        banners (:panel/system-banners (:uf/db result))]
    ;; Should still have one banner (replaced, not appended)
    (-> (expect (count banners))
        (.toBe 1))
    ;; New banner should have new message
    (-> (expect (:message (first banners)))
        (.toBe "Done!"))))

(defn- test_show_system_banner_with_category_does_not_replace_different_category []
  (let [existing-banner {:id "msg-1" :type "info" :message "Loading..." :category "loading"}
        state {:panel/system-banners [existing-banner]}
        result (panel-actions/handle-action state uf-data [:editor/ax.show-system-banner "success" "Saved!" "save"])
        banners (:panel/system-banners (:uf/db result))]
    ;; Should have two banners (different categories)
    (-> (expect (count banners))
        (.toBe 2))))

(defn- test_show_system_banner_without_category_appends_normally []
  (let [existing-banner {:id "msg-1" :type "info" :message "Existing..." :category "existing"}
        state {:panel/system-banners [existing-banner]}
        result (panel-actions/handle-action state uf-data [:editor/ax.show-system-banner "success" "New!"])
        banners (:panel/system-banners (:uf/db result))]
    ;; Should have two banners (no category to replace)
    (-> (expect (count banners))
        (.toBe 2))))

(describe "panel system banner with category"
          (fn []
            (test ":editor/ax.show-system-banner with category replaces existing banner" test_show_system_banner_with_category_replaces_existing_banner)
            (test ":editor/ax.show-system-banner does not replace different category" test_show_system_banner_with_category_does_not_replace_different_category)
            (test ":editor/ax.show-system-banner without category appends normally" test_show_system_banner_without_category_appends_normally)))
