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
   :panel/script-id nil
   :panel/save-status nil})

(def uf-data {:system/now 1234567890})

(describe "panel handle-action"
          (fn []
            (test ":editor/ax.set-code updates code"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code "new code"])]
                      (-> (expect (:panel/code (:uf/db result)))
                          (.toBe "new code")))))

            (test ":editor/ax.set-script-name updates name"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-name "My Script"])]
                      (-> (expect (:panel/script-name (:uf/db result)))
                          (.toBe "My Script")))))

            (test ":editor/ax.set-script-match updates match pattern"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-match "*://github.com/*"])]
                      (-> (expect (:panel/script-match (:uf/db result)))
                          (.toBe "*://github.com/*")))))

            (test ":editor/ax.set-script-description updates description"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-description "A helpful description"])]
                      (-> (expect (:panel/script-description (:uf/db result)))
                          (.toBe "A helpful description")))))

            (test ":editor/ax.update-scittle-status updates status"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.update-scittle-status :loaded])]
                      (-> (expect (:panel/scittle-status (:uf/db result)))
                          (.toBe :loaded)))))

            (test ":editor/ax.clear-results empties results"
                  (fn []
                    (let [state-with-results (assoc initial-state :panel/results [{:type :input :text "code"}])
                          result (panel-actions/handle-action state-with-results uf-data [:editor/ax.clear-results])]
                      (-> (expect (:panel/results (:uf/db result)))
                          (.toEqual [])))))

            (test ":editor/ax.clear-code empties code"
                  (fn []
                    (let [state-with-code (assoc initial-state :panel/code "(+ 1 2)")
                          result (panel-actions/handle-action state-with-code uf-data [:editor/ax.clear-code])]
                      (-> (expect (:panel/code (:uf/db result)))
                          (.toBe "")))))

            (test ":editor/ax.handle-eval-result adds output to results"
                  (fn []
                    (let [state-evaluating (assoc initial-state :panel/evaluating? true)
                          result (panel-actions/handle-action state-evaluating uf-data [:editor/ax.handle-eval-result {:result "42"}])
                          new-state (:uf/db result)]
                      (-> (expect (:panel/evaluating? new-state))
                          (.toBe false))
                      (-> (expect (count (:panel/results new-state)))
                          (.toBe 1))
                      (-> (expect (:type (first (:panel/results new-state))))
                          (.toBe :output)))))

            (test ":editor/ax.handle-eval-result adds error to results"
                  (fn []
                    (let [state-evaluating (assoc initial-state :panel/evaluating? true)
                          result (panel-actions/handle-action state-evaluating uf-data [:editor/ax.handle-eval-result {:error "oops"}])
                          new-state (:uf/db result)]
                      (-> (expect (:panel/evaluating? new-state))
                          (.toBe false))
                      (-> (expect (:type (first (:panel/results new-state))))
                          (.toBe :error)))))

            (test ":editor/ax.load-script-for-editing populates all fields"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data
                                                              [:editor/ax.load-script-for-editing
                                                               "script-123"
                                                               "Test Script"
                                                               "*://example.com/*"
                                                               "(println \"hello\")"
                                                               "A description"])
                          new-state (:uf/db result)]
                      (-> (expect (:panel/script-id new-state))
                          (.toBe "script-123"))
                      (-> (expect (:panel/script-name new-state))
                          (.toBe "Test Script"))
                      (-> (expect (:panel/script-match new-state))
                          (.toBe "*://example.com/*"))
                      (-> (expect (:panel/code new-state))
                          (.toBe "(println \"hello\")"))
                      (-> (expect (:panel/script-description new-state))
                          (.toBe "A description")))))

            (test ":editor/ax.load-script-for-editing handles missing description"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data
                                                              [:editor/ax.load-script-for-editing
                                                               "script-123"
                                                               "Test Script"
                                                               "*://example.com/*"
                                                               "(println \"hello\")"])
                          new-state (:uf/db result)]
                      ;; Missing description should default to empty string
                      (-> (expect (:panel/script-description new-state))
                          (.toBe "")))))))

(describe "panel eval action"
          (fn []
            (test ":editor/ax.eval with empty code returns nil"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.eval])]
                      (-> (expect result)
                          (.toBeNull)))))

            (test ":editor/ax.eval when already evaluating returns nil"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(+ 1 2)")
                                    (assoc :panel/evaluating? true))
                          result (panel-actions/handle-action state uf-data [:editor/ax.eval])]
                      (-> (expect result)
                          (.toBeNull)))))

            (test ":editor/ax.eval with loaded scittle triggers direct eval"
                  (fn []
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
                          (.toBe :editor/fx.eval-in-page)))))

            (test ":editor/ax.eval without scittle triggers inject-and-eval"
                  (fn []
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
                          (.toBe :editor/fx.inject-and-eval)))))))

(describe "panel save action"
          (fn []
            (test ":editor/ax.save-script with missing fields shows error"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.save-script])]
                      (-> (expect (:type (:panel/save-status (:uf/db result))))
                          (.toBe :error)))))

            (test ":editor/ax.save-script with complete fields triggers save effect"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(println \"hi\")")
                                    (assoc :panel/script-name "My Script")
                                    (assoc :panel/script-match "*://example.com/*"))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          new-state (:uf/db result)]
                      ;; Should show success status
                      (-> (expect (:type (:panel/save-status new-state)))
                          (.toBe :success))
                      ;; Should keep normalized name after save (not clear it)
                      (-> (expect (:panel/script-name new-state))
                          (.toBe "my_script.cljs"))
                      ;; Should set original-name to match
                      (-> (expect (:panel/original-name new-state))
                          (.toBe "my_script.cljs"))
                      ;; Should trigger save effect
                      (-> (expect (first (first (:uf/fxs result))))
                          (.toBe :editor/fx.save-script)))))

            (test ":editor/ax.save-script uses existing script-id when editing with unchanged name"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(println \"hi\")")
                                    (assoc :panel/script-name "my_script.cljs")
                                    (assoc :panel/script-match "*://example.com/*")
                                    (assoc :panel/script-id "existing-id")
                                    (assoc :panel/original-name "my_script.cljs"))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          [_fx-name script] (first (:uf/fxs result))]
                      ;; Should use existing id when name hasn't changed
                      (-> (expect (:script/id script))
                          (.toBe "existing-id")))))

            (test ":editor/ax.save-script creates new script when name changed (fork/copy)"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(println \"hi\")")
                                    (assoc :panel/script-name "New Name")
                                    (assoc :panel/script-match "*://example.com/*")
                                    (assoc :panel/script-id "existing-id")
                                    (assoc :panel/original-name "old_name.cljs"))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          [_fx-name script] (first (:uf/fxs result))
                          new-state (:uf/db result)]
                      ;; Should create new ID (fork/copy behavior) - not preserve existing
                      (-> (expect (:script/id script))
                          (.toMatch (js/RegExp. "^script-\\d+$")))
                      (-> (expect (:script/id script))
                          (.not.toBe "existing-id"))
                      ;; Name should be normalized
                      (-> (expect (:script/name script))
                          (.toBe "new_name.cljs"))
                      ;; Status should say "Created"
                      (-> (expect (:text (:panel/save-status new-state)))
                          (.toContain "Created")))))

            (test ":editor/ax.save-script generates timestamp-based ID for new scripts"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(println \"hi\")")
                                    (assoc :panel/script-name "My Cool Script")
                                    (assoc :panel/script-match "*://example.com/*"))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          [_fx-name script] (first (:uf/fxs result))]
                      ;; ID should be timestamp-based for new scripts (starts with "script-")
                      (-> (expect (:script/id script))
                          (.toMatch (js/RegExp. "^script-\\d+$")))
                      ;; Name is normalized for display consistency
                      (-> (expect (:script/name script))
                          (.toBe "my_cool_script.cljs")))))

            (test ":editor/ax.save-script includes description when provided"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(println \"hi\")")
                                    (assoc :panel/script-name "My Script")
                                    (assoc :panel/script-match "*://example.com/*")
                                    (assoc :panel/script-description "A helpful description"))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          [_fx-name script] (first (:uf/fxs result))]
                      (-> (expect (:script/description script))
                          (.toBe "A helpful description")))))

            (test ":editor/ax.save-script omits description when empty"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(println \"hi\")")
                                    (assoc :panel/script-name "My Script")
                                    (assoc :panel/script-match "*://example.com/*")
                                    (assoc :panel/script-description ""))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          [_fx-name script] (first (:uf/fxs result))]
                      ;; Empty description should not be included in script
                      (-> (expect (:script/description script))
                          (.toBeUndefined)))))

            (test ":editor/ax.save-script keeps description after save"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(println \"hi\")")
                                    (assoc :panel/script-name "My Script")
                                    (assoc :panel/script-match "*://example.com/*")
                                    (assoc :panel/script-description "A description"))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          new-state (:uf/db result)]
                      ;; Description should be preserved after save
                      (-> (expect (:panel/script-description new-state))
                          (.toBe "A description")))))

            (test ":editor/ax.save-script preserves vector match without double-wrapping"
                  (fn []
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
                          (.toBe "*://foo.com/*")))))

            (test ":editor/ax.save-script includes require from manifest hints"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(ns test)")
                                    (assoc :panel/script-name "My Script")
                                    (assoc :panel/script-match "*://example.com/*")
                                    (assoc :panel/manifest-hints {:require ["scittle://reagent.js"]}))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          [_fx-name script] (first (:uf/fxs result))]
                      (-> (expect (:script/require script))
                          (.toEqual ["scittle://reagent.js"])))))))

;; ============================================================
;; Phase 2: Manifest-driven metadata tests
;; ============================================================

(describe "panel set-code with manifest parsing"
          (fn []
            (test ":editor/ax.set-code parses manifest and returns dxs to update fields"
                  (fn []
                    (let [code "^{:epupp/script-name \"GitHub Tweaks\"
  :epupp/site-match \"https://github.com/*\"
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
                          (.toBeTruthy)))))

            (test ":editor/ax.set-code stores manifest hints for normalization"
                  (fn []
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
                          (.toBe "GitHub Tweaks")))))

            (test ":editor/ax.set-code stores unknown keys in hints"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/author \"PEZ\"
  :epupp/version \"1.0\"}
(ns test)"
                          result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
                          new-state (:uf/db result)]
                      (-> (expect (:unknown-keys (:panel/manifest-hints new-state)))
                          (.toContain "epupp/author"))
                      (-> (expect (:unknown-keys (:panel/manifest-hints new-state)))
                          (.toContain "epupp/version")))))

            (test ":editor/ax.set-code clears hints when no manifest"
                  (fn []
                    (let [state-with-hints (assoc initial-state
                                                  :panel/manifest-hints {:name-normalized? true})
                          code "(defn foo [] 42)"
                          result (panel-actions/handle-action state-with-hints uf-data [:editor/ax.set-code code])
                          new-state (:uf/db result)]
                      ;; Should clear hints when no manifest found
                      (-> (expect (:panel/manifest-hints new-state))
                          (.toBeFalsy)))))

            (test ":editor/ax.set-code handles site-match as vector"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/site-match [\"https://github.com/*\" \"https://gist.github.com/*\"]}
(ns test)"
                          result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
                          dxs (:uf/dxs result)
                          ;; Find the set-script-match action
                          match-action (first (filter #(= (first %) :editor/ax.set-script-match) dxs))]
                      ;; Should pass vector to set-script-match
                      (-> (expect (second match-action))
                          (.toEqual ["https://github.com/*" "https://gist.github.com/*"])))))

            (test ":editor/ax.set-code stores run-at invalid flag in hints"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/run-at \"invalid-timing\"}
(ns test)"
                          result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
                          new-state (:uf/db result)]
                      (-> (expect (:run-at-invalid? (:panel/manifest-hints new-state)))
                          (.toBe true))
                      (-> (expect (:raw-run-at (:panel/manifest-hints new-state)))
                          (.toBe "invalid-timing")))))

            (test ":editor/ax.set-code stores require in manifest hints"
                  (fn []
                    (let [code "{:epupp/script-name \"test.cljs\"
  :epupp/require [\"scittle://reagent.js\" \"scittle://pprint.js\"]}
(ns test)"
                          result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
                          new-state (:uf/db result)]
                      ;; Should store require in hints
                      (-> (expect (:require (:panel/manifest-hints new-state)))
                          (.toEqual ["scittle://reagent.js" "scittle://pprint.js"])))))

            (test ":editor/ax.set-code stores empty require when missing"
                  (fn []
                    (let [code "{:epupp/script-name \"test.cljs\"}
(ns test)"
                          result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
                          new-state (:uf/db result)]
                      ;; Should store empty vector when require is missing
                      (-> (expect (:require (:panel/manifest-hints new-state)))
                          (.toEqual [])))))))

;; ============================================================
;; Panel initialization tests
;; ============================================================

(describe "panel initialization action"
          (fn []
            (test ":editor/ax.initialize-editor with saved code parses manifest"
                  (fn []
                    (let [saved-code "{:epupp/script-name \"My Script\"
 :epupp/site-match \"https://example.com/*\"
 :epupp/description \"Test script\"}

(ns my-script)
(println \"hello\")"
                          result (panel-actions/handle-action
                                  initial-state uf-data
                                  [:editor/ax.initialize-editor
                                   {:code saved-code
                                    :script-id "script-123"
                                    :original-name "my_script.cljs"}])
                          new-state (:uf/db result)
                          dxs (:uf/dxs result)]
                      ;; Code should be set
                      (-> (expect (:panel/code new-state))
                          (.toBe saved-code))
                      ;; Script ID should be set
                      (-> (expect (:panel/script-id new-state))
                          (.toBe "script-123"))
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
                          (.toBeTruthy)))))

            (test ":editor/ax.initialize-editor with no saved code uses default script"
                  (fn []
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
                          (.toBeTruthy)))))

            (test ":editor/ax.initialize-editor with empty code uses default script"
                  (fn []
                    (let [result (panel-actions/handle-action
                                  initial-state uf-data
                                  [:editor/ax.initialize-editor {:code ""}])
                          new-state (:uf/db result)]
                      ;; Empty code should trigger default script
                      (-> (expect (:panel/code new-state))
                          (.toContain "hello_world.cljs")))))))

;; ============================================================
;; New Script action tests
;; ============================================================

(describe "panel new script action"
          (fn []
            (test ":editor/ax.new-script resets to default script"
                  (fn []
                    (let [state-with-script (-> initial-state
                                                (assoc :panel/code "(println \"custom code\")")
                                                (assoc :panel/script-name "custom_script.cljs")
                                                (assoc :panel/script-match "*://custom.com/*")
                                                (assoc :panel/script-description "Custom description")
                                                (assoc :panel/script-id "script-123")
                                                (assoc :panel/original-name "custom_script.cljs")
                                                (assoc :panel/save-status {:type :success :text "Saved"}))
                          result (panel-actions/handle-action state-with-script uf-data [:editor/ax.new-script])
                          new-state (:uf/db result)]
                      ;; Code should be reset to default script
                      (-> (expect (:panel/code new-state))
                          (.toContain "hello_world.cljs"))
                      (-> (expect (:panel/code new-state))
                          (.toContain "(ns hello-world)"))
                      ;; Script ID should be cleared
                      (-> (expect (:panel/script-id new-state))
                          (.toBeNull))
                      ;; Original name should be cleared
                      (-> (expect (:panel/original-name new-state))
                          (.toBeNull))
                      ;; Save status should be cleared
                      (-> (expect (:panel/save-status new-state))
                          (.toBeNull)))))

            (test ":editor/ax.new-script clears persisted state"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.new-script])
                          fxs (:uf/fxs result)]
                      ;; Should trigger clear-persisted-state effect
                      (-> (expect (some #(= (first %) :editor/fx.clear-persisted-state) fxs))
                          (.toBeTruthy)))))

            (test ":editor/ax.new-script returns dxs to parse default manifest"
                  (fn []
                    (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.new-script])
                          dxs (:uf/dxs result)]
                      ;; Should have dxs to set fields from default manifest
                      (-> (expect (some #(= (first %) :editor/ax.set-script-name) dxs))
                          (.toBeTruthy))
                      (-> (expect (some #(= (first %) :editor/ax.set-script-match) dxs))
                          (.toBeTruthy)))))

            (test ":editor/ax.new-script preserves results array"
                  (fn []
                    (let [state-with-results (-> initial-state
                                                 (assoc :panel/results [{:type :input :text "(+ 1 2)"}
                                                                        {:type :output :text "3"}]))
                          result (panel-actions/handle-action state-with-results uf-data [:editor/ax.new-script])
                          new-state (:uf/db result)]
                      ;; Results should be preserved
                      (-> (expect (count (:panel/results new-state)))
                          (.toBe 2))
                      (-> (expect (:type (first (:panel/results new-state))))
                          (.toBe :input)))))))

(describe "panel selection actions"
          (fn []
            (test ":editor/ax.set-selection updates selection state"
                  (fn []
                    (let [selection {:start 0 :end 7 :text "(+ 1 2)"}
                          result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-selection selection])]
                      (-> (expect (:panel/selection (:uf/db result)))
                          (.toEqual {:start 0 :end 7 :text "(+ 1 2)"})))))

            (test ":editor/ax.set-selection clears selection with nil"
                  (fn []
                    (let [state-with-selection (assoc initial-state :panel/selection {:start 0 :end 5 :text "hello"})
                          result (panel-actions/handle-action state-with-selection uf-data [:editor/ax.set-selection nil])]
                      (-> (expect (:panel/selection (:uf/db result)))
                          (.toBeNull)))))

            (test ":editor/ax.eval-selection with selection evaluates selected text"
                  (fn []
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
                          (.toBe "(* 3 4)")))))

            (test ":editor/ax.eval-selection without selection evaluates full code"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(+ 1 2)")
                                    (assoc :panel/selection nil)
                                    (assoc :panel/scittle-status :loaded))
                          result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
                          [_effect-name effect-code] (first (:uf/fxs result))]
                      ;; Should fall back to full code
                      (-> (expect effect-code)
                          (.toBe "(+ 1 2)")))))

            (test ":editor/ax.eval-selection with empty selection text evaluates full code"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(+ 1 2)")
                                    (assoc :panel/selection {:start 3 :end 3 :text ""})
                                    (assoc :panel/scittle-status :loaded))
                          result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
                          [_effect-name effect-code] (first (:uf/fxs result))]
                      ;; Empty selection (cursor position) falls back to full code
                      (-> (expect effect-code)
                          (.toBe "(+ 1 2)")))))

            (test ":editor/ax.eval-selection with empty code and empty selection returns nil"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/selection {:start 0 :end 0 :text ""}))
                          result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])]
                      ;; Should return nil when both code and selection are empty
                      (-> (expect result)
                          (.toBeNull)))))

            (test ":editor/ax.eval-selection when already evaluating returns nil"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(+ 1 2)")
                                    (assoc :panel/selection {:start 0 :end 7 :text "(+ 1 2)"})
                                    (assoc :panel/evaluating? true))
                          result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])]
                      (-> (expect result)
                          (.toBeNull)))))

            (test ":editor/ax.eval-selection without scittle triggers inject-and-eval"
                  (fn []
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
                          (.toBe :loading)))))))
