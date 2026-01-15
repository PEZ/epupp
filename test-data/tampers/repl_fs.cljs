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
;; REPL File System API (epupp.fs namespace):
;; - epupp.fs/ls     - List all scripts with metadata
;; - epupp.fs/cat    - Get script code by name
;; - epupp.fs/save!  - Create/update script from code with manifest
;; - epupp.fs/mv!    - Rename a script
;; - epupp.fs/rm!    - Delete a script
;;
;; REPL utilities (epupp.repl namespace):
;; - epupp.repl/manifest! - Load Scittle libraries
;;
;; All functions return promises. Use promesa for ergonomic async.

(comment
  (epupp.repl/manifest! {:epupp/require ["scittle://promesa.js"
                                         "scittle://pprint.js"]})

  (require '[promesa.core :as p])
  (require '[cljs.pprint :refer [print-table]])

  ;; ===== SETUP =====
  ;; Verify fs functions exist
  (fn? epupp.fs/ls)
  ;; => true

  (fn? epupp.fs/cat)
  ;; => true

  (fn? epupp.fs/save!)
  ;; => true

  (fn? epupp.fs/mv!)
  ;; => true

  (fn? epupp.fs/rm!)
  ;; => true

  ;; ===== epupp.fs/ls - List Scripts =====
  ;; Returns vector of {:fs/name :fs/enabled :fs/match}

  (def !ls-result (atom :pending))
  (-> (epupp.fs/ls)
      (.then (fn [scripts] (reset! !ls-result scripts))))

  @!ls-result
  ;; => [{:fs/name "GitHub Gist Installer (Built-in)", :fs/enabled true, :fs/match [...]} ...]

  ;; Check structure of first script
  (first @!ls-result)
  ;; => {:fs/name "...", :fs/enabled true/false, :fs/match ["pattern" ...]}

  (p/let [ls-result (epupp.fs/ls)]
    (def ls-result ls-result))
  ;; Check the result after eval of the p/let
  ls-result
  ;; => [{:fs/name "...", :fs/enabled true, :fs/match [...]} ...]

  ;; ===== Formatted Output with print-table =====
  ;; Use cljs.pprint/print-table for nicely formatted listings
  ;; Wrap with with-out-str to capture output in REPL

  ;; Full table with all columns
  (with-out-str (print-table ls-result))
  ;; =>
  ;; |                         :fs/name | :fs/enabled |  :fs/match |
  ;; |----------------------------------+-------------+------------|
  ;; |                 hello_world.cljs |       false |      ["*"] |
  ;; | GitHub Gist Installer (Built-in) |        true |    ["..."] |
  ;; ...

  ;; Select specific columns
  (with-out-str (print-table [:fs/name :fs/enabled] ls-result))
  ;; =>
  ;; |                         :fs/name | :fs/enabled |
  ;; |----------------------------------+-------------|
  ;; |                 hello_world.cljs |       false |
  ;; ...


  ;; ===== epupp.fs/cat - Get Script Code =====
  ;; Returns code string or nil if not found

  (def !cat-result (atom :pending))
  (-> (epupp.fs/cat "GitHub Gist Installer (Built-in)")
      (.then (fn [code] (reset! !cat-result code))))

  @!cat-result
  ;; => "{:epupp/script-name ...}\n\n(ns ...)"

  ;; Verify manifest is in the code
  (clojure.string/includes? @!cat-result ":epupp/script-name")
  ;; => true

  ;; Non-existent script returns nil
  (def !cat-nil (atom :pending))
  (-> (epupp.fs/cat "does-not-exist.cljs")
      (.then (fn [code] (reset! !cat-nil code))))

  @!cat-nil
  ;; => nil

  ;; ===== epupp.fs/save! - Create Script =====
  ;; Returns {:fs/success true :fs/name "..."} or {:fs/success false :fs/error "..."}

  (def test-script-code
    "{:epupp/script-name \"repl-test-script\"
 :epupp/site-match \"*\"
 :epupp/description \"Test script created via REPL fs API\"}

(ns repl-test-script)

(js/console.log \"Hello from REPL test script!\")")

  (def !save-result (atom :pending))
  (-> (epupp.fs/save! test-script-code)
      (.then (fn [r] (reset! !save-result r))))

  @!save-result
  ;; => {:fs/success true, :fs/name "repl_test_script.cljs", :fs/error nil}

  ;; Verify script appears in ls
  (def !ls-after-save (atom :pending))
  (-> (epupp.fs/ls)
      (.then (fn [scripts] (reset! !ls-after-save scripts))))

  (some #(= (:fs/name %) "repl_test_script.cljs") @!ls-after-save)
  ;; => true

  ;; ===== epupp.fs/mv! - Rename Script =====
  ;; Returns {:fs/success true} or {:fs/success false :fs/error "..."}

  (def !mv-result (atom :pending))
  (-> (epupp.fs/mv! "repl_test_script.cljs" "repl_renamed_script.cljs")
      (.then (fn [r] (reset! !mv-result r))))

  @!mv-result
  ;; => {:fs/success true, :fs/error nil}

  ;; Verify rename: new name exists, old name gone
  (def !ls-after-mv (atom :pending))
  (-> (epupp.fs/ls)
      (.then (fn [scripts] (reset! !ls-after-mv scripts))))

  [(some #(= (:fs/name %) "repl_renamed_script.cljs") @!ls-after-mv)
   (some #(= (:fs/name %) "repl_test_script.cljs") @!ls-after-mv)]
  ;; => [true nil]

  ;; ===== epupp.fs/rm! - Delete Script =====
  ;; Returns {:fs/success true} or {:fs/success false :fs/error "..."}
  ;; Built-in scripts cannot be deleted.

  (def !rm-result (atom :pending))
  (-> (epupp.fs/rm! "repl_renamed_script.cljs")
      (.then (fn [r] (reset! !rm-result r))))

  @!rm-result
  ;; => {:fs/success true, :fs/error nil}

  ;; Verify script is gone
  (def !ls-after-rm (atom :pending))
  (-> (epupp.fs/ls)
      (.then (fn [scripts] (reset! !ls-after-rm scripts))))

  (some #(= (:fs/name %) "repl_renamed_script.cljs") @!ls-after-rm)
  ;; => nil

  ;; Built-in scripts are protected
  (def !rm-builtin (atom :pending))
  (-> (epupp.fs/rm! "GitHub Gist Installer (Built-in)")
      (.then (fn [r] (reset! !rm-builtin r))))

  @!rm-builtin
  ;; => {:fs/success false, :fs/error "Cannot delete built-in scripts"}

  ;; ===== CLEANUP =====
  ;; Remove test script when done testing
  ;; (epupp.fs/rm! "repl_test_script.cljs")

  :rcf)
