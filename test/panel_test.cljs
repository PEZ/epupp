(ns panel-test
  "Tests for panel action handlers - pure state transitions"
  (:require ["vitest" :as vt]
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
   :panel/script-id nil
   :panel/save-status nil})

(def uf-data {:system/now 1234567890})

(vt/describe "panel handle-action"
  (fn []
    (vt/test ":editor/ax.set-code updates code"
          (fn []
            (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code "new code"])]
              (-> (vt/expect (:panel/code (:uf/db result)))
                  (.toBe "new code")))))

    (vt/test ":editor/ax.set-script-name updates name"
          (fn []
            (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-name "My Script"])]
              (-> (vt/expect (:panel/script-name (:uf/db result)))
                  (.toBe "My Script")))))

    (vt/test ":editor/ax.set-script-match updates match pattern"
          (fn []
            (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-match "*://github.com/*"])]
              (-> (vt/expect (:panel/script-match (:uf/db result)))
                  (.toBe "*://github.com/*")))))

    (vt/test ":editor/ax.update-scittle-status updates status"
          (fn []
            (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.update-scittle-status :loaded])]
              (-> (vt/expect (:panel/scittle-status (:uf/db result)))
                  (.toBe :loaded)))))

    (vt/test ":editor/ax.clear-results empties results"
          (fn []
            (let [state-with-results (assoc initial-state :panel/results [{:type :input :text "code"}])
                  result (panel-actions/handle-action state-with-results uf-data [:editor/ax.clear-results])]
              (-> (vt/expect (:panel/results (:uf/db result)))
                  (.toEqual [])))))

    (vt/test ":editor/ax.clear-code empties code"
          (fn []
            (let [state-with-code (assoc initial-state :panel/code "(+ 1 2)")
                  result (panel-actions/handle-action state-with-code uf-data [:editor/ax.clear-code])]
              (-> (vt/expect (:panel/code (:uf/db result)))
                  (.toBe "")))))

    (vt/test ":editor/ax.handle-eval-result adds output to results"
          (fn []
            (let [state-evaluating (assoc initial-state :panel/evaluating? true)
                  result (panel-actions/handle-action state-evaluating uf-data [:editor/ax.handle-eval-result {:result "42"}])
                  new-state (:uf/db result)]
              (-> (vt/expect (:panel/evaluating? new-state))
                  (.toBe false))
              (-> (vt/expect (count (:panel/results new-state)))
                  (.toBe 1))
              (-> (vt/expect (:type (first (:panel/results new-state))))
                  (.toBe :output)))))

    (vt/test ":editor/ax.handle-eval-result adds error to results"
          (fn []
            (let [state-evaluating (assoc initial-state :panel/evaluating? true)
                  result (panel-actions/handle-action state-evaluating uf-data [:editor/ax.handle-eval-result {:error "oops"}])
                  new-state (:uf/db result)]
              (-> (vt/expect (:panel/evaluating? new-state))
                  (.toBe false))
              (-> (vt/expect (:type (first (:panel/results new-state))))
                  (.toBe :error)))))

    (vt/test ":editor/ax.load-script-for-editing populates all fields"
          (fn []
            (let [result (panel-actions/handle-action initial-state uf-data
                                                       [:editor/ax.load-script-for-editing
                                                        "script-123"
                                                        "Test Script"
                                                        "*://example.com/*"
                                                        "(println \"hello\")"])
                  new-state (:uf/db result)]
              (-> (vt/expect (:panel/script-id new-state))
                  (.toBe "script-123"))
              (-> (vt/expect (:panel/script-name new-state))
                  (.toBe "Test Script"))
              (-> (vt/expect (:panel/script-match new-state))
                  (.toBe "*://example.com/*"))
              (-> (vt/expect (:panel/code new-state))
                  (.toBe "(println \"hello\")")))))))

(vt/describe "panel eval action"
  (fn []
    (vt/test ":editor/ax.eval with empty code returns nil"
          (fn []
            (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.eval])]
              (-> (vt/expect result)
                  (.toBeNull)))))

    (vt/test ":editor/ax.eval when already evaluating returns nil"
          (fn []
            (let [state (-> initial-state
                            (assoc :panel/code "(+ 1 2)")
                            (assoc :panel/evaluating? true))
                  result (panel-actions/handle-action state uf-data [:editor/ax.eval])]
              (-> (vt/expect result)
                  (.toBeNull)))))

    (vt/test ":editor/ax.eval with loaded scittle triggers direct eval"
          (fn []
            (let [state (-> initial-state
                            (assoc :panel/code "(+ 1 2)")
                            (assoc :panel/scittle-status :loaded))
                  result (panel-actions/handle-action state uf-data [:editor/ax.eval])
                  new-state (:uf/db result)]
              (-> (vt/expect (:panel/evaluating? new-state))
                  (.toBe true))
              (-> (vt/expect (count (:panel/results new-state)))
                  (.toBe 1))
              ;; Should trigger fx.eval-in-page
              (-> (vt/expect (first (first (:uf/fxs result))))
                  (.toBe :editor/fx.eval-in-page)))))

    (vt/test ":editor/ax.eval without scittle triggers inject-and-eval"
          (fn []
            (let [state (-> initial-state
                            (assoc :panel/code "(+ 1 2)")
                            (assoc :panel/scittle-status :unknown))
                  result (panel-actions/handle-action state uf-data [:editor/ax.eval])
                  new-state (:uf/db result)]
              (-> (vt/expect (:panel/evaluating? new-state))
                  (.toBe true))
              (-> (vt/expect (:panel/scittle-status new-state))
                  (.toBe :loading))
              ;; Should trigger fx.inject-and-eval
              (-> (vt/expect (first (first (:uf/fxs result))))
                  (.toBe :editor/fx.inject-and-eval)))))))

(vt/describe "panel save action"
  (fn []
    (vt/test ":editor/ax.save-script with missing fields shows error"
          (fn []
            (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.save-script])]
              (-> (vt/expect (:type (:panel/save-status (:uf/db result))))
                  (.toBe :error)))))

    (vt/test ":editor/ax.save-script with complete fields triggers save effect"
          (fn []
            (let [state (-> initial-state
                            (assoc :panel/code "(println \"hi\")")
                            (assoc :panel/script-name "My Script")
                            (assoc :panel/script-match "*://example.com/*"))
                  result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                  new-state (:uf/db result)]
              ;; Should show success status
              (-> (vt/expect (:type (:panel/save-status new-state)))
                  (.toBe :success))
              ;; Should clear fields after save
              (-> (vt/expect (:panel/script-name new-state))
                  (.toBe ""))
              (-> (vt/expect (:panel/script-match new-state))
                  (.toBe ""))
              ;; Should trigger save effect
              (-> (vt/expect (first (first (:uf/fxs result))))
                  (.toBe :editor/fx.save-script)))))

    (vt/test ":editor/ax.save-script uses existing script-id when editing"
          (fn []
            (let [state (-> initial-state
                            (assoc :panel/code "(println \"hi\")")
                            (assoc :panel/script-name "My Script")
                            (assoc :panel/script-match "*://example.com/*")
                            (assoc :panel/script-id "existing-id"))
                  result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
                  [_fx-name script] (first (:uf/fxs result))]
              ;; Should use existing id, not generate new one
              (-> (vt/expect (:script/id script))
                  (.toBe "existing-id")))))))
