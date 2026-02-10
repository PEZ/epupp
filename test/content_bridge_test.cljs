(ns content-bridge-test
  (:require ["vitest" :refer [describe test expect]]
            [content-bridge :refer [message-registry]]))

(defn- test-all-entries-have-required-keys []
  (doseq [[_ entry] message-registry]
    (-> (expect (:msg/sources entry))
        (.toBeTruthy))
    (-> (expect (:msg/auth entry))
        (.not.toBeUndefined))
    (-> (expect (:msg/response? entry))
        (.not.toBeUndefined))))

(defn- test-all-sources-are-valid-source-strings []
  (let [valid-sources #{"epupp-page" "epupp-userscript"}]
    (doseq [[_ entry] message-registry]
      (doseq [source (:msg/sources entry)]
        (-> (expect (contains? valid-sources source))
            (.toBe true))))))

(defn- test-fire-and-forget-messages-do-not-require-fs-sync-auth []
  (doseq [[_ entry] message-registry]
    (when-not (:msg/response? entry)
      (-> (expect (:msg/auth entry))
          (.not.toBe :auth/fs-sync)))))

(defn- test-response-type-overrides-are-present-where-expected []
  (-> (expect (:msg/response-type (get message-registry "load-manifest")))
      (.toBe "manifest-response")))

(describe "message-registry integrity"
          (fn []
            (test "all entries have required keys"
                  test-all-entries-have-required-keys)
            (test "all sources are valid source strings"
                  test-all-sources-are-valid-source-strings)
            (test "fire-and-forget messages do not require fs-sync auth"
                  test-fire-and-forget-messages-do-not-require-fs-sync-auth)
            (test "response type overrides are present where expected"
                  test-response-type-overrides-are-present-where-expected)))

(defn- test-unregistered-types-return-nil []
  (-> (expect (get message-registry "evil-message"))
      (.toBeUndefined))
  (-> (expect (get message-registry "pattern-approved"))
      (.toBeUndefined))
  (-> (expect (get message-registry "evaluate-script"))
      (.toBeUndefined)))

(defn- test-source-filtering-restricts-access []
  ;; list-scripts should only be accessible from epupp-page
  (let [list-entry (get message-registry "list-scripts")]
    (-> (expect (contains? (:msg/sources list-entry) "epupp-page"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources list-entry) "epupp-userscript"))
        (.toBe false)))
  ;; sponsor-status should only be accessible from epupp-userscript
  (let [sponsor-entry (get message-registry "sponsor-status")]
    (-> (expect (contains? (:msg/sources sponsor-entry) "epupp-userscript"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources sponsor-entry) "epupp-page"))
        (.toBe false)))
  ;; save-script should only be accessible from epupp-page (WS-gated FS sync)
  (let [save-entry (get message-registry "save-script")]
    (-> (expect (contains? (:msg/sources save-entry) "epupp-page"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources save-entry) "epupp-userscript"))
        (.toBe false))))

(describe "message-registry access control"
          (fn []
            (test "unregistered message types return nil"
                  test-unregistered-types-return-nil)
            (test "source filtering restricts access correctly"
                  test-source-filtering-restricts-access)))

;; ============================================================
;; Web Installer Message Registry Tests
;; ============================================================

(defn- test-check-script-exists-registry-entry []
  (let [entry (get message-registry "check-script-exists")]
    ;; Must be registered
    (-> (expect entry) (.toBeTruthy))
    ;; Only accessible from epupp-page
    (-> (expect (contains? (:msg/sources entry) "epupp-page"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources entry) "epupp-userscript"))
        (.toBe false))
    ;; No auth required (read-only check)
    (-> (expect (:msg/auth entry))
        (.toBe :auth/none))
    ;; Response-bearing message
    (-> (expect (:msg/response? entry))
        (.toBe true))))

(defn- test-web-installer-save-script-registry-entry []
  (let [entry (get message-registry "web-installer-save-script")]
    ;; Must be registered
    (-> (expect entry) (.toBeTruthy))
    ;; Only accessible from epupp-page
    (-> (expect (contains? (:msg/sources entry) "epupp-page"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources entry) "epupp-userscript"))
        (.toBe false))
    ;; Uses domain-whitelist auth
    (-> (expect (:msg/auth entry))
        (.toBe :auth/domain-whitelist))
    ;; Response-bearing message
    (-> (expect (:msg/response? entry))
        (.toBe true))))

(describe "web installer message registry"
          (fn []
            (test "check-script-exists has correct registry entry"
                  test-check-script-exists-registry-entry)
            (test "web-installer-save-script has correct registry entry"
                  test-web-installer-save-script-registry-entry)))
