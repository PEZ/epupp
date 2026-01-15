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

  (p/let [ls-result (epupp.fs/ls)]
    (def ls-result ls-result))
  ;; Check result after p/let evaluation by evaluateing `ls-results`

  ;; Check structure of first script
  (first ls-result)
  ;; => {:fs/name "...", :fs/enabled true/false, :fs/match ["pattern" ...]}

  ;; ===== Formatted Output with print-table =====
  ;; Use cljs.pprint/print-table for nicely formatted listings

  ;; Full table with all columns
  (print-table ls-result)
  ;; prints (in the browser console...)
  ;; |                         :fs/name | :fs/enabled |  :fs/match |
  ;; |----------------------------------+-------------+------------|
  ;; |                 hello_world.cljs |       false |      ["*"] |
  ;; | GitHub Gist Installer (Built-in) |        true |    ["..."] |
  ;; ...

  ;; Select specific columns
  (print-table [:fs/name :fs/enabled] ls-result)
  ;; =>
  ;; |                         :fs/name | :fs/enabled |
  ;; |----------------------------------+-------------|
  ;; |                 hello_world.cljs |       false |
  ;; ...


  ;; ===== epupp.fs/cat - Get Script Code =====
  ;; Returns code string or nil if not found

  (p/let [cat-result (epupp.fs/cat "GitHub Gist Installer (Built-in)")]
    (def cat-result cat-result))

  cat-result
  ;; => "{:epupp/script-name ...}\n\n(ns ...)"

  ;; Verify manifest is in the code
  (clojure.string/includes? cat-result ":epupp/script-name")
  ;; => true

  ;; Non-existent script returns nil
  (p/let [cat-nil (epupp.fs/cat "does-not-exist.cljs")]
    (def cat-nil cat-nil))

  cat-nil
  ;; => nil

  ;; ===== epupp.fs/save! - Create Script =====
  ;; Returns {:fs/success true :fs/name "..."} or {:fs/success false :fs/error "..."}

  (def test-script-code
    "{:epupp/script-name \"repl-test-script\"
 :epupp/site-match \"*\"
 :epupp/description \"Test script created via REPL fs API\"}

(ns repl-test-script)

(js/console.log \"Hello from REPL test script!\")")

  (p/let [save-result (epupp.fs/save! test-script-code)]
    (def save-result save-result))
  ;; => {:fs/success true, :fs/name "repl_test_script.cljs", :fs/error nil}

  ;; Verify script appears in ls
  (p/let [ls-after-save (epupp.fs/ls)]
    (def ls-after-save ls-after-save))

  (some #(= (:fs/name %) "repl_test_script.cljs") ls-after-save)
  ;; => true

  ;; ===== epupp.fs/mv! - Rename Script =====
  ;; Returns {:fs/success true} or {:fs/success false :fs/error "..."}

  (p/let [mv-result (epupp.fs/mv! "test" "test.cljs")]
    (def mv-result mv-result))
  ;; => {:fs/success true, :fs/error nil}

  ;; Verify rename: new name exists, old name gone
  (p/let [ls-after-mv (epupp.fs/ls)]
    (def ls-after-mv ls-after-mv))

  [(some #(= (:fs/name %) "repl_renamed_script.cljs") ls-after-mv)
   (some #(= (:fs/name %) "repl_test_script.cljs") ls-after-mv)]
  ;; => [true nil]

  ;; ===== epupp.fs/rm! - Delete Script =====
  ;; Returns {:fs/success true} or {:fs/success false :fs/error "..."}
  ;; Built-in scripts cannot be deleted.

  (p/let [rm-result (epupp.fs/rm! "repl_renamed_script.cljs")]
    (def rm-result rm-result))

  rm-result
  ;; => {:fs/success true, :fs/error nil}

  ;; Verify script is gone
  (p/let [ls-after-rm (epupp.fs/ls)]
    (def ls-after-rm ls-after-rm))

  (some #(= (:fs/name %) "repl_renamed_script.cljs") ls-after-rm)
  ;; => nil

  ;; Built-in scripts are protected
  (p/let [rm-builtin (epupp.fs/rm! "GitHub Gist Installer (Built-in)")]
    (def rm-builtin rm-builtin))

  rm-builtin
  ;; => {:fs/success false, :fs/error "Cannot delete built-in scripts"}

  ;; ===== CLEANUP =====
  ;; Remove test script when done testing
  ;; (epupp.fs/rm! "repl_test_script.cljs")

  :rcf)
