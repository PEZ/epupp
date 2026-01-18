(epupp.repl/manifest! {:epupp/require ["scittle://promesa.js"]})

(ns tampers.fs-api-exercise
  (:require [promesa.core :as p]
            epupp.fs))

(comment
  ;; ===== READ OPERATIONS (always work) =====

  ;; List all scripts
  (p/let [ls-result (epupp.fs/ls {:fs/ls-hidden? true})]
    (def ls-result ls-result))
  ;; PEZ: Checks out!

  ;; Show existing script
  (p/let [show-result (epupp.fs/show "GitHub Gist Installer (Built-in)")]
    (def show-result show-result))
  ;; PEZ: Checks out! And even if we shouldn't list it, it makes sense that we show it.

  ;; Show non-existent script returns nil
  (p/let [show-nil (epupp.fs/show "does-not-exist.cljs")]
    (def show-nil show-nil))
  ;; PEZ: Checks out!

  ;; Bulk show - map of name to code (nil for missing)
  (p/let [show-bulk (epupp.fs/show ["GitHub Gist Installer (Built-in)" "does-not-exist.cljs"])]
    (def show-bulk show-bulk))
  ;; PEZ: Checks out!

  ;; ===== WRITE OPERATIONS (require FS REPL Sync enabled) =====
  ;; PEZ: I tested all with the setting disabled and they all rejected the promise correctly
  ;; PEZ: We should show an error banner in both the panel and the popup, and the extension icon should get an error badge.

  ;; Save new script
  (-> (p/let [save-result (epupp.fs/save! "{:epupp/script-name \"test-save-1\"}\n(ns test1)" {:fs/force? true})]
        (def save-result save-result))
      (p/catch (fn [e] (def save-error (.-message e)))))

  ;; Save does not overwrite existing
  (-> (p/let [save-overwrite-result (epupp.fs/save! "{:epupp/script-name \"test-save-1\"}\n(ns test1-v2)")]
        (def save-overwrite-result save-overwrite-result))
      (p/catch (fn [e] (def save-overwrite-error (.-message e)))))
  ;; Checks out!

  ;; Save with force overwrites existing
  (-> (p/let [save-force-result (epupp.fs/save! "{:epupp/script-name \"test-save-1\"}\n(ns test1-v2)" {:fs/force? true})]
        (def save-force-result save-force-result))
      (p/catch (fn [e] (def save-force-error (.-message e)))))
  ;; PEZ: Checks out!

  ;; Save does not overwrite built-in
  (-> (p/let [save-built-in-result (epupp.fs/save! "{:epupp/script-name \"GitHub Gist Installer (Built-in)\"}\n(ns no-built-in-saving-for-you)")]
        (def save-built-in-result save-built-in-result))
      (p/catch (fn [e] (def save-built-in-error (.-message e)))))
  ;; PEZ: Checks out!

  ;; Save with force does not overwrite built-in
  (-> (p/let [save-built-in-force-result (epupp.fs/save! "{:epupp/script-name \"GitHub Gist Installer (Built-in)\"}\n(ns no-built-in-saving-for-you)" {:fs/force? true})]
        (def save-built-in-force-result save-built-in-force-result))
      (p/catch (fn [e] (def save-built-in-force-error (.-message e)))))
  ;; PEZ: Checks out!

  ;; Bulk save
  (-> (p/let [bulk-save-result (epupp.fs/save! ["{:epupp/script-name \"bulk-1\"}\n(ns b1)"
                                                "{:epupp/script-name \"bulk-2\"}\n(ns b2)"]
                                               {:fs/force? true})]
        (def bulk-save-result bulk-save-result))
      (p/catch (fn [e] (def bulk-save-error (.-message e)))))
  ;; PEZ: Message says bulk-2 was saved, should say “2 files" saved

  ;; Rename script
  (-> (p/let [mv-result (epupp.fs/mv! "test_save_1.cljs" "test_renamed.cljs")]
        (def mv-result mv-result))
      (p/catch (fn [e] (def mv-error (.-message e)))))
  ;; PEZ: Checks out! Also: doing ut twice gave the expected reject

  ;; Rename non-existent rejects
  (-> (p/let [mv-noexist-result (epupp.fs/mv! "i-dont-exist.cljs" "neither-will-i.cljs")]
        (def mv-noexist-result mv-noexist-result))
      (p/catch (fn [e] (def mv-noexist-error (.-message e)))))
  ;; PEZ: Checks out!

  ;; Rename built-in rejects
  (-> (p/let [mv-builtin-result (epupp.fs/mv! "GitHub Gist Installer (Built-in)" "renamed-builtin.cljs")]
        (def mv-builtin-result mv-builtin-result))
      (p/catch (fn [e] (def mv-builtin-error (.-message e)))))
  ;; PEZ: Checks out!

  ;; Delete script - returns :fs/existed? true
  (-> (p/let [rm-result (epupp.fs/rm! "test_renamed.cljs")]
        (def rm-result rm-result))
      (p/catch (fn [e] (def rm-error (.-message e)))))
  ;; PEZ: Checks out!

  ;; Delete non-existent - rejects with Script not found
  (-> (p/let [rm-noexist-result (epupp.fs/rm! "does-not-exist.cljs")]
        (def rm-noexist-result rm-noexist-result))
      (p/catch (fn [e] (def rm-noexist-error (.-message e)))))
  ;; PEZ: Checks out!

  ;; Delete built-in rejects
  (-> (p/let [rm-builtin-result (epupp.fs/rm! "GitHub Gist Installer (Built-in)")]
        (def rm-builtin-result rm-builtin-result))
      (p/catch (fn [e] (def rm-builtin-error (.-message e)))))
  ;; PEZ: Checks out!

  ;; Bulk delete
  (-> (p/let [bulk-rm-result (epupp.fs/rm! ["bulk_1.cljs" "bulk_2.cljs"])]
        (def bulk-rm-result bulk-rm-result))
      (p/catch (fn [e] (def bulk-rm-error (.-message e)))))
  ;; PEZ: Reports "bulk_2.cljs" as deleted, should report 2 files as deleted

  ;; Bulk delete - mixed existing/non-existing
  (-> (p/let [bulk-rm-result (epupp.fs/rm! ["bulk_1.cljs" "does-not-exist.cljs" "bulk_2.cljs"])]
        (def bulk-rm-result bulk-rm-result))
      (p/catch (fn [e] (def bulk-rm-error (.-message e)))))
  ;; PEZ: Checks out!

  ;; Bulk delete - mixed existing/non-existing (re-create the bulk files first)
  (-> (p/let [bulk-rm-w-built-in-result (epupp.fs/rm! ["bulk_1.cljs" "GitHub Gist Installer (Built-in)" "bulk_2.cljs" "does-not-exist.cljs"])]
        (def bulk-rm-w-built-in-result bulk-rm-w-built-in-result))
      (p/catch (fn [e] (def bulk-rm-w-built-in-error (.-message e)))))
  ;; PEZ: Checks out!

  ;; ===== CLEANUP =====
  (epupp.fs/rm! ["test_save_1.cljs"
                 "test_renamed.cljs"
                 "bulk_1.cljs"
                 "bulk_2.cljs"])

  :rcf)
