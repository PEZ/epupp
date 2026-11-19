(ns storage.own-write-echo-test
  "Tests that late onChanged echoes of the background's own writes leave the
   in-memory scripts alone, while writes from other contexts still apply."
  (:require ["vitest" :refer [describe test expect beforeEach afterEach]]
            [storage :as storage]))

(def ^:private !store (atom nil))
(def ^:private !echoes (atom []))
(def ^:private !listener (atom nil))
(def ^:private !originals (atom nil))

(defn- stub-set [items callback]
  (let [changes (js-obj)]
    (doseq [k (js/Object.keys items)]
      (aset changes k #js {:newValue (js/structuredClone (aget items k))}))
    (js/Object.assign @!store (js/structuredClone items))
    (swap! !echoes conj changes)
    (when callback (callback))))

(def ^:private chrome-storage-stub
  #js {:local #js {:get (fn [_keys] (js/Promise.resolve (js/structuredClone @!store)))
                   :set stub-set
                   :remove (fn [_keys callback] (when callback (callback)))}
       :onChanged #js {:addListener (fn [f] (reset! !listener f))}})

(defn- stub-fetch [_url]
  (js/Promise.resolve #js {:ok true
                           :status 200
                           :text (fn [] (js/Promise.resolve "(ns builtin)"))}))

(defn- install-stubs! []
  (reset! !store #js {})
  (reset! !echoes [])
  (reset! !listener nil)
  (reset! !originals {:storage js/chrome.storage :fetch js/globalThis.fetch})
  (set! js/chrome.storage chrome-storage-stub)
  (set! js/globalThis.fetch stub-fetch))

(defn- restore-stubs! []
  (set! js/chrome.storage (:storage @!originals))
  (set! js/globalThis.fetch (:fetch @!originals)))

(defn- script-ids []
  (mapv :script/id (storage/get-scripts)))

(defn- ^:async test-late-own-echo-keeps-added-scripts []
  (js-await (storage/init!))
  (-> (expect (script-ids)) (.toContain "epupp-builtin-tools"))
  ;; The echo of the first sync write holds only the first built-in.
  (@!listener (first @!echoes) "local")
  (-> (expect (script-ids)) (.toContain "epupp-builtin-storage"))
  (-> (expect (script-ids)) (.toContain "epupp-builtin-tools")))

(defn- ^:async test-write-from-other-context-applies []
  (js-await (storage/init!))
  (let [stored (.-scripts @!store)
        user-script #js {:id "script-1" :code "(ns user)" :enabled false}
        changes #js {:scripts #js {:newValue (.concat stored #js [user-script])}}]
    (@!listener changes "local")
    (-> (expect (script-ids)) (.toContain "script-1"))))

(describe "storage onChanged echoes"
          (fn []
            (beforeEach install-stubs!)
            (afterEach restore-stubs!)
            (test "late echo of an own write keeps scripts added after it"
                  test-late-own-echo-keeps-added-scripts)
            (test "write from another context updates the scripts"
                  test-write-from-other-context-applies)))
