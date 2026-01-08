(ns manifest-parser-test
  (:require ["vitest" :refer [describe test expect]]
            [manifest-parser :as mp]))

(describe "manifest-parser"
          (fn []
            (test "extracts manifest from metadata map"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"\n  :epupp/site-match \"https://example.com/*\"\n  :epupp/description \"desc\"\n  :epupp/run-at \"document-start\"}\n(ns test)"
                          manifest (mp/extract-manifest code)]
                      (-> (expect (:script-name manifest))
                          (.toBe "test.cljs"))
                      (-> (expect (:site-match manifest))
                          (.toBe "https://example.com/*"))
                      (-> (expect (:description manifest))
                          (.toBe "desc"))
                      (-> (expect (:run-at manifest))
                          (.toBe "document-start")))))

            (test "defaults run-at when omitted"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"}\n(ns test)"
                          manifest (mp/extract-manifest code)]
                      (-> (expect (:run-at manifest))
                          (.toBe "document-idle"))
                      (-> (expect (mp/get-run-at code))
                          (.toBe "document-idle")))))

            (test "validates run-at values"
                  (fn []
                    (let [code "^{:epupp/script-name \"test.cljs\"\n  :epupp/run-at \"nope\"}\n(ns test)"
                          manifest (mp/extract-manifest code)]
                      (-> (expect (:run-at manifest))
                          (.toBe "document-idle")))))

            (test "returns nil for code without manifest"
                  (fn []
                    (let [code "(defn foo [] 42)"
                          manifest (mp/extract-manifest code)]
                      ;; Clojure nil becomes JS undefined
                      (-> (expect manifest)
                          (.toBeUndefined)))))

            (test "has-manifest? detects presence"
                  (fn []
                    (-> (expect (mp/has-manifest? "^{:epupp/script-name \"x.cljs\"} (ns x)"))
                        (.toBe true))
                    (-> (expect (mp/has-manifest? "(ns x)"))
                        (.toBe false))))))
