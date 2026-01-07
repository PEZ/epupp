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
                      ;; Should clear fields after save
                      (-> (expect (:panel/script-name new-state))
                          (.toBe ""))
                      (-> (expect (:panel/script-match new-state))
                          (.toBe ""))
                      ;; Should trigger save effect
                      (-> (expect (first (first (:uf/fxs result))))
                          (.toBe :editor/fx.save-script)))))

            (test ":editor/ax.save-script uses existing script-id when editing"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(println \"hi\")")
                                    (assoc :panel/script-name "My Script")
                                    (assoc :panel/script-match "*://example.com/*")
                                    (assoc :panel/script-id "existing-id"))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          [_fx-name script] (first (:uf/fxs result))]
                      ;; Should use existing id, not generate new one
                      (-> (expect (:script/id script))
                          (.toBe "existing-id")))))

            (test ":editor/ax.save-script normalizes name and derives ID for new scripts"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(println \"hi\")")
                                    (assoc :panel/script-name "My Cool Script")
                                    (assoc :panel/script-match "*://example.com/*"))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          [_fx-name script] (first (:uf/fxs result))]
                      ;; ID derived from normalized name for new scripts
                      (-> (expect (:script/id script))
                          (.toBe "my_cool_script.cljs"))
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

            (test ":editor/ax.save-script clears description after save"
                  (fn []
                    (let [state (-> initial-state
                                    (assoc :panel/code "(println \"hi\")")
                                    (assoc :panel/script-name "My Script")
                                    (assoc :panel/script-match "*://example.com/*")
                                    (assoc :panel/script-description "A description"))
                          result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                          new-state (:uf/db result)]
                      (-> (expect (:panel/script-description new-state))
                          (.toBe "")))))))
