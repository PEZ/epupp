(ns tampers.fs-api-exercise
  (:require epupp.fs))

(comment
  ;; ===== READ OPERATIONS (always work) =====

  ;; List all scripts, exceot built-ins enless hiddens are asked for
  (defn ^:async list-scripts []
    (let [ls-result (await (epupp.fs/ls #_{:fs/ls-hidden? true}))]
      (def ls-result ls-result)))
  (list-scripts)

  ;; Show existing script
  (defn ^:async show-script []
    (let [show-result (await (epupp.fs/show "epupp/web_userscript_installer.cljs"))]
      (def show-result show-result)))
  (show-script)

  ;; Show non-existent script returns nil
  (defn ^:async show-nonexistent []
    (let [show-nil (await (epupp.fs/show "does-not-exist.cljs"))]
      (def show-nil show-nil)))
  (show-nonexistent)

  ;; Bulk show - map of name to code (nil for missing)
  (defn ^:async show-bulk-scripts []
    (let [show-bulk (await (epupp.fs/show ["epupp/web_userscript_installer.cljs" "does-not-exist.cljs"]))]
      (def show-bulk show-bulk)))
  (show-bulk-scripts)

  ;; ===== WRITE OPERATIONS (require FS REPL Sync enabled) =====
  ;; PEZ: I tested all with the setting disabled and they all rejected the promise correctly
  ;; PEZ: We should show an error banner in both the panel and the popup, and the extension icon should get an error badge.

  ;; Save new script
  (defn ^:async save-new-script []
    (try
      (let [save-result (await (epupp.fs/save!
                                "{:epupp/script-name \"test-save-1\"
                                  :epupp/auto-run-match \"*\"}
                                 (ns test1)" #_{:fs/force? true}))]
        (def save-result save-result))
      (catch :default e (def save-error (.-message e)))))
  (save-new-script)

  ;; Save does not overwrite existing
  (defn ^:async save-no-overwrite []
    (try
      (let [save-overwrite-result (await (epupp.fs/save! "{:epupp/script-name \"test-save-1\"}\n(ns test1-v2)"))]
        (def save-overwrite-result save-overwrite-result))
      (catch :default e (def save-overwrite-error (.-message e)))))
  (save-no-overwrite)

  ;; Save with force overwrites existing
  (defn ^:async save-with-force []
    (try
      (let [save-force-result (await (epupp.fs/save! "{:epupp/script-name \"test-save-1\"}\n(ns test1-v2)" {:fs/force? true}))]
        (def save-force-result save-force-result))
      (catch :default e (def save-force-error (.-message e)))))
  (save-with-force)

  ;; Save does not allow names starting with epupp/
  (defn ^:async save-epupp-prefix []
    (try
      (let [epupp-prefix-save-result (await (epupp.fs/save!
                                             "{:epupp/script-name \"epupp/test-save-1\"}"
                                             #_{:fs/force? true}))]
        (def epupp-prefix-save-result epupp-prefix-save-result))
      (catch :default e (def epupp-prefix-save-error (.-message e)))))
  (save-epupp-prefix)

  ;; Save does not overwrite built-in
  (defn ^:async save-over-builtin []
    (try
      (let [save-built-in-result (await (epupp.fs/save! "{:epupp/script-name \"epupp/gist_installer.cljs\"}\n(ns no-built-in-saving-for-you)"))]
        (def save-built-in-result save-built-in-result))
      (catch :default e (def save-built-in-error (.-message e)))))
  (save-over-builtin)

  ;; Save with force does not overwrite built-in
  (defn ^:async save-force-over-builtin []
    (try
      (let [save-built-in-force-result (await (epupp.fs/save! "{:epupp/script-name \"epupp/gist_installer.cljs\"}\n(ns no-built-in-saving-for-you)" {:fs/force? true}))]
        (def save-built-in-force-result save-built-in-force-result))
      (catch :default e (def save-built-in-force-error (.-message e)))))
  (save-force-over-builtin)
  ;; PEZ: Checks out!

  ;; Bulk save
  (defn ^:async bulk-save []
    (try
      (let [bulk-save-result (await (epupp.fs/save! ["{:epupp/script-name \"bulk-1\"}\n(ns b1)"
                                                     "{:epupp/script-name \"bulk-2\"}\n(ns b2)"]
                                                    {:fs/force? true}))]
        (def bulk-save-result bulk-save-result))
      (catch :default e (def bulk-save-error (.-message e)))))
  (bulk-save)
  ;; PEZ: Message says bulk-2 was saved, should say “2 files" saved

  ;; Rename script
  (defn ^:async rename-script []
    (try
      (let [mv-result (await (epupp.fs/mv! "test_save_1.cljs" "test_renamed.cljs"))]
        (def mv-result mv-result))
      (catch :default e (def mv-error (.-message e)))))
  (rename-script)
  ;; PEZ: Checks out! Also: doing ut twice gave the expected reject

  ;; Rename script to start with `epupp/` rejects
  (defn ^:async rename-to-epupp-prefix []
    (try
      (let [epupp-prefix-mv-result (await (epupp.fs/mv! "test_save_1.cljs" "epupp/test_renamed.cljs"))]
        (def epupp-prefix-mv-result epupp-prefix-mv-result))
      (catch :default e (def epupp-prefix-mv-error (.-message e)))))
  (rename-to-epupp-prefix)
  ;; PEZ: Checks out! Also: doing ut twice gave the expected reject

  ;; Rename non-existent rejects
  (defn ^:async rename-nonexistent []
    (try
      (let [mv-noexist-result (await (epupp.fs/mv! "i-dont-exist.cljs" "neither-will-i.cljs"))]
        (def mv-noexist-result mv-noexist-result))
      (catch :default e (def mv-noexist-error (.-message e)))))
  (rename-nonexistent)
  ;; PEZ: Checks out!

  ;; Rename built-in rejects
  (defn ^:async rename-builtin []
    (try
      (let [mv-builtin-result (await (epupp.fs/mv! "epupp/gist_installer.cljs" "renamed-builtin.cljs"))]
        (def mv-builtin-result mv-builtin-result))
      (catch :default e (def mv-builtin-error (.-message e)))))
  (rename-builtin)
  ;; PEZ: Checks out!

  ;; Delete script - returns :fs/existed? true
  (defn ^:async delete-script []
    (try
      (let [rm-result (await (epupp.fs/rm! "test_renamed.cljs"))]
        (def rm-result rm-result))
      (catch :default e (def rm-error (.-message e)))))
  (delete-script)
  ;; PEZ: Checks out!

  ;; Delete non-existent - rejects with Script not found
  (defn ^:async delete-nonexistent []
    (try
      (let [rm-noexist-result (await (epupp.fs/rm! "does-not-exist.cljs"))]
        (def rm-noexist-result rm-noexist-result))
      (catch :default e (def rm-noexist-error (.-message e)))))
  (delete-nonexistent)
  ;; PEZ: Checks out!

  ;; Delete built-in rejects
  (defn ^:async delete-builtin []
    (try
      (let [rm-builtin-result (await (epupp.fs/rm! "epupp/gist_installer.cljs"))]
        (def rm-builtin-result rm-builtin-result))
      (catch :default e (def rm-builtin-error (.-message e)))))
  (delete-builtin)
  ;; PEZ: Checks out!

  ;; Bulk delete
  (defn ^:async bulk-delete []
    (try
      (let [bulk-rm-result (await (epupp.fs/rm! ["bulk_1.cljs" "bulk_2.cljs"]))]
        (def bulk-rm-result bulk-rm-result))
      (catch :default e (def bulk-rm-error (.-message e)))))
  (bulk-delete)
  ;; PEZ: Reports "bulk_2.cljs" as deleted, should report 2 files as deleted

  ;; Bulk delete - mixed existing/non-existing
  (defn ^:async bulk-delete-mixed []
    (try
      (let [bulk-rm-result (await (epupp.fs/rm! ["bulk_1.cljs" "does-not-exist.cljs" "bulk_2.cljs"]))]
        (def bulk-rm-result bulk-rm-result))
      (catch :default e (def bulk-rm-error (.-message e)))))
  (bulk-delete-mixed)
  ;; PEZ: Checks out!

  ;; Bulk delete - mixed existing/non-existing (re-create the bulk files first)
  (defn ^:async bulk-delete-with-builtin []
    (try
      (let [bulk-rm-w-built-in-result (await (epupp.fs/rm! ["bulk_1.cljs" "epupp/gist_installer.cljs" "bulk_2.cljs" "does-not-exist.cljs"]))]
        (def bulk-rm-w-built-in-result bulk-rm-w-built-in-result))
      (catch :default e (def bulk-rm-w-built-in-error (.-message e)))))
  (bulk-delete-with-builtin)
  ;; PEZ: Checks out!

  ;; ===== CLEANUP =====
  (defn ^:async cleanup []
    (await (epupp.fs/rm! ["test_save_1.cljs"
                          "test_renamed.cljs"
                          "bulk_1.cljs"
                          "bulk_2.cljs"])))
  (cleanup)

  :rcf)
