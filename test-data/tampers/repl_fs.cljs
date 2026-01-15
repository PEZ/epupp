(ns tampers.repl-fs)
;;
;; Prerequisites:
;; 1. Build extension: bb build:dev
;; 2. Load extension in Chrome
;; 3. Start browser-nrepl: bb browser-nrepl
;; 4. Navigate to any page (e.g., https://example.com)
;; 5. Click Epupp extension icon -> Connect
;; 6. Connect your editor to nREPL port 12345
;;
;; REPL File System API:
;; - epupp/ls     - List all scripts with metadata
;; - epupp/cat    - Get script code by name
;; - epupp/save!  - Create/update script from code with manifest
;; - epupp/mv!    - Rename a script
;; - epupp/rm!    - Delete a script
;;
;; All functions return promises. Use promesa for ergonomic async.

(comment
  (epupp/manifest! {:epupp/require ["scittle://promesa.js"
                                    "scittle://pprint.js"]})

  (require '[promesa.core :as p])
  (require '[cljs.pprint :refer [print-table]])

  ;; ===== SETUP =====
  ;; Verify fs functions exist
  (fn? epupp/ls)
  ;; => true

  (fn? epupp/cat)
  ;; => true

  (fn? epupp/save!)
  ;; => true

  (fn? epupp/mv!)
  ;; => true

  (fn? epupp/rm!)
  ;; => true

  ;; ===== epupp/ls - List Scripts =====
  ;; Returns vector of {:name :enabled :match}

  (def !ls-result (atom :pending))
  (-> (epupp/ls)
      (.then (fn [scripts] (reset! !ls-result scripts))))

  @!ls-result
  ;; => [{:name "GitHub Gist Installer (Built-in)", :enabled true, :match [...]} ...]

  ;; Check structure of first script
  (first @!ls-result)
  ;; => {:name "...", :enabled true/false, :match ["pattern" ...]}

  (p/let [ls-result (epupp/ls)]
    (def ls-result ls-result))
  ;; Check the result after eval of the p/let
  ls-result

  ;; ===== Formatted Output with print-table =====
  ;; Use cljs.pprint/print-table for nicely formatted listings
  ;; Wrap with with-out-str to capture output in REPL

  ;; Full table with all columns
  (with-out-str (print-table ls-result))
  ;; =>
  ;; |                            :name | :enabled |     :match |
  ;; |----------------------------------+----------+------------|
  ;; |                 hello_world.cljs |    false |      ["*"] |
  ;; | GitHub Gist Installer (Built-in) |     true | ["..."] |
  ;; ...

  ;; Select specific columns
  (with-out-str (print-table [:name :enabled] ls-result))
  ;; =>
  ;; |                            :name | :enabled |
  ;; |----------------------------------+----------|
  ;; |                 hello_world.cljs |    false |
  ;; ...


  ;; ===== epupp/cat - Get Script Code =====
  ;; Returns code string or nil if not found

  (def !cat-result (atom :pending))
  (-> (epupp/cat "GitHub Gist Installer (Built-in)")
      (.then (fn [code] (reset! !cat-result code))))

  @!cat-result
  ;; => "{:epupp/script-name ...}\n\n(ns ...)"

  ;; Verify manifest is in the code
  (clojure.string/includes? @!cat-result ":epupp/script-name")
  ;; => true

  ;; Non-existent script returns nil
  (def !cat-nil (atom :pending))
  (-> (epupp/cat "does-not-exist.cljs")
      (.then (fn [code] (reset! !cat-nil code))))

  @!cat-nil
  ;; => nil

  ;; ===== epupp/save! - Create Script =====
  ;; Returns {:success true :name "..."} or {:success false :error "..."}

  (def test-script-code
    "{:epupp/script-name \"repl-test-script\"
 :epupp/site-match \"*\"
 :epupp/description \"Test script created via REPL fs API\"}

(ns repl-test-script)

(js/console.log \"Hello from REPL test script!\")")

  (def !save-result (atom :pending))
  (-> (epupp/save! test-script-code)
      (.then (fn [r] (reset! !save-result r))))

  @!save-result
  ;; => {:success true, :name "repl_test_script.cljs", :error nil}

  ;; Verify script appears in ls
  (def !ls-after-save (atom :pending))
  (-> (epupp/ls)
      (.then (fn [scripts] (reset! !ls-after-save scripts))))

  (some #(= (:name %) "repl_test_script.cljs") @!ls-after-save)
  ;; => true

  ;; ===== epupp/mv! - Rename Script =====
  ;; Returns {:success true} or {:success false :error "..."}

  (def !mv-result (atom :pending))
  (-> (epupp/mv! "repl_test_script.cljs" "repl_renamed_script.cljs")
      (.then (fn [r] (reset! !mv-result r))))

  @!mv-result
  ;; => {:success true, :error nil}

  ;; Verify rename: new name exists, old name gone
  (def !ls-after-mv (atom :pending))
  (-> (epupp/ls)
      (.then (fn [scripts] (reset! !ls-after-mv scripts))))

  [(some #(= (:name %) "repl_renamed_script.cljs") @!ls-after-mv)
   (some #(= (:name %) "repl_test_script.cljs") @!ls-after-mv)]
  ;; => [true nil]

  ;; ===== epupp/rm! - Delete Script =====
  ;; Returns {:success true} or {:success false :error "..."}
  ;; Built-in scripts cannot be deleted.

  (def !rm-result (atom :pending))
  (-> (epupp/rm! "repl_renamed_script.cljs")
      (.then (fn [r] (reset! !rm-result r))))

  @!rm-result
  ;; => {:success true, :error nil}

  ;; Verify script is gone
  (def !ls-after-rm (atom :pending))
  (-> (epupp/ls)
      (.then (fn [scripts] (reset! !ls-after-rm scripts))))

  (some #(= (:name %) "repl_renamed_script.cljs") @!ls-after-rm)
  ;; => nil

  ;; Built-in scripts are protected
  (def !rm-builtin (atom :pending))
  (-> (epupp/rm! "GitHub Gist Installer (Built-in)")
      (.then (fn [r] (reset! !rm-builtin r))))

  @!rm-builtin
  ;; => {:success false, :error "Cannot delete built-in scripts"}

  ;; ===== CLEANUP =====
  ;; Remove test script when done testing
  ;; (epupp/rm! "repl_test_script.cljs")

  :rcf)
