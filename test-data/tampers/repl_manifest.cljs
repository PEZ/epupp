(ns tampers.repl-manifest)
;;
;; Prerequisites:
;; 1. Build extension: bb build:dev
;; 2. Load extension in Chrome
;; 3. Start browser-nrepl: bb browser-nrepl
;; 4. Navigate to any page (e.g., https://example.com)
;; 5. Click Epupp extension icon -> Connect
;; 6. Connect your editor to nREPL port 12345
;;
;; Then evaluate these forms one by one from your editor:

(comment
  ;; Step 1: Verify epupp namespace exists
  (fn? epupp/manifest!)
  ;; => true

  ;; Step 2: Load Replicant library
  (epupp/manifest! {:epupp/inject ["scittle://replicant.js"]})
  ;; => #object[Promise [object Promise]]
  ;; Wait ~1 second for injection to complete

  ;; Step 3: Verify Replicant is available
  (resolve 'replicant.dom/render)
  ;; => #'replicant.dom/render

  ;; Step 4: Render something with Replicant
  (require '[replicant.dom :as r])

  (let [container (js/document.createElement "div")]
    (set! (.-id container) "epupp-repl-banner")
    (set! (.. container -style -cssText)
          "position: fixed; top: 20px; right: 20px; padding: 20px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; border-radius: 12px; font-family: system-ui; z-index: 99999; box-shadow: 0 4px 20px rgba(0,0,0,0.3);")
    (.appendChild js/document.body container)
    (r/render container
              [:div
               [:h2 {:style {:margin "0 0 8px 0"}} "Epupp REPL"]
               [:p {:style {:margin 0}} "Live tampering your web!"]]))
  ;; => nil (but you should see a purple banner in the top-right!)

  ;; Step 5: Verify DOM was created
  (boolean (js/document.getElementById "epupp-repl-banner"))
  ;; => true

  ;; Step 6: Test idempotency - call manifest! again
  (epupp/manifest! {:epupp/inject ["scittle://replicant.js"]})
  ;; Should not error, libraries already loaded

  ;; Step 7: Test pprint library
  (epupp/manifest! {:epupp/inject ["scittle://pprint.js"]})
  ;; Wait a moment...

  (require '[cljs.pprint :refer [pprint]])
  (with-out-str (pprint {:name "Epupp" :feature "REPL manifest" :works? true}))
  ;; => "{:name \"Epupp\", :feature \"REPL manifest\", :works? true}\n"

  ;; Cleanup: Remove the banner
  (when-let [el (js/document.getElementById "epupp-repl-banner")]
    (.remove el))
  :rcf)