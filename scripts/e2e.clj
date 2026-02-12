(ns e2e
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-server :as server]
            [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))


;; ============================================================
;; Test Server Management
;; ============================================================

(def ^:private test-server-port 18080)

;; Two browser-nrepl servers for multi-tab testing
(def ^:private browser-nrepl-port-1 12345)
(def ^:private browser-ws-port-1 12346)
(def ^:private browser-nrepl-port-2 12347)
(def ^:private browser-ws-port-2 12348)

;; Default Playwright retries for flakiness tolerance
(def ^:private default-retries 2)

;; E2E output directory (project-local, gitignored)
;; Agents can read output with read_file instead of shell redirection.
(def ^:private e2e-tmp-dir ".tmp")
(def ^:private e2e-output-file (str e2e-tmp-dir "/e2e-output.txt"))
(def ^:private e2e-nrepl-log (str e2e-tmp-dir "/e2e-nrepl.log"))
(def ^:private e2e-history-dir (str e2e-tmp-dir "/e2e-history"))
(def ^:private default-history-count 10)

(defn- ensure-tmp-dir!
  "Ensure the .tmp directory exists for test output files."
  []
  (fs/create-dirs e2e-tmp-dir))

(defn- log-writer
  "Create an appending writer to the nREPL subprocess log file."
  []
  (ensure-tmp-dir!)
  (io/writer e2e-nrepl-log :append true))

(defn- wait-for-port
  "Wait for a port to become available, with timeout."
  [port timeout-ms]
  (let [start (System/currentTimeMillis)
        deadline (+ start timeout-ms)]
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        false
        (if (try
              (with-open [_ (java.net.Socket. "localhost" port)]
                true)
              (catch Exception _ false))
          true
          (do
            (Thread/sleep 100)
            (recur)))))))

(defn with-test-server
  "Execute f with an HTTP test server running on port 18080.
   Server is started before f and stopped after (even on exception)."
  [f]
  (let [stop-fn (server/serve {:port test-server-port :dir "test-data/pages"})]
    (try
      (println (format "Test server available at http://localhost:%d" test-server-port))
      (Thread/sleep 300) ; Give server time to fully start
      (f)
      (finally
        (stop-fn)
        (println "Test server stopped")))))

(defn- start-browser-nrepl-process
  "Start a browser-nrepl process on given ports.
   Output goes to e2e-log-file for clean console. Returns the process."
  [nrepl-port ws-port]
  (let [writer (log-writer)]
    (.write writer (str "\n=== browser-nrepl " nrepl-port "/" ws-port " ===\n"))
    (.flush writer)
    (p/process ["bb" "browser-nrepl"
                "--nrepl-port" (str nrepl-port)
                "--websocket-port" (str ws-port)]
               {:out writer :err writer})))

(defn with-browser-nrepls
  "Execute f with two browser-nrepl relay servers running.
   Enables multi-tab testing with different ports."
  [f]
  (println "Starting browser-nrepl servers...")
  (let [proc1 (start-browser-nrepl-process browser-nrepl-port-1 browser-ws-port-1)
        proc2 (start-browser-nrepl-process browser-nrepl-port-2 browser-ws-port-2)]
    (try
      (if (and (wait-for-port browser-nrepl-port-1 5000)
               (wait-for-port browser-nrepl-port-2 5000))
        (do
          (println (format "browser-nrepl #1 ready on ports %d / %d" browser-nrepl-port-1 browser-ws-port-1))
          (println (format "browser-nrepl #2 ready on ports %d / %d" browser-nrepl-port-2 browser-ws-port-2))
          (f))
        (throw (ex-info "browser-nrepl servers failed to start" {})))
      (finally
        (p/destroy-tree proc1)
        (p/destroy-tree proc2)
        (Thread/sleep 300)
        (println "browser-nrepl servers stopped")))))

(defn run-e2e-tests!
  "Run Playwright E2E tests with test server and two browser-nrepls.
   Subprocess output goes to .tmp/ for clean console output.
   Exits with Playwright's exit code without Babashka stack trace noise."
  [args]
  (println (str "nREPL log: " e2e-nrepl-log))
  (with-test-server
    #(with-browser-nrepls
       (fn []
         (let [args (into [(str "--retries=" default-retries)] args)
               result (apply p/shell {:continue true} "npx playwright test" args)]
           (System/exit (:exit result)))))))

;; ============================================================
;; E2E Timing Report
;; ============================================================

(defn- extract-specs-from-suite
  "Recursively extract specs from a suite and its nested suites.
   Returns seq of {:name string :duration-ms int :file string}"
  [suite file]
  (let [file (or (:file suite) file)
        ;; Extract specs at this level
        direct-specs (for [spec (:specs suite)
                           test (:tests spec)
                           result (:results test)]
                       {:name (:title spec)
                        :file (or (:file spec) file)
                        :duration-ms (:duration result)})
        ;; Recursively extract from nested suites
        nested-specs (mapcat #(extract-specs-from-suite % file)
                             (:suites suite))]
    (concat direct-specs nested-specs)))

(defn- extract-test-timings
  "Extract test name and duration from Playwright JSON report structure.
   Handles nested suites created by .describe blocks.
   Returns seq of {:name string :duration-ms int :file string}"
  [json-data]
  (mapcat #(extract-specs-from-suite % nil) (:suites json-data)))

(defn- format-duration
  "Format milliseconds as human-readable string"
  [ms]
  (cond
    (>= ms 1000) (format "%.2fs" (/ ms 1000.0))
    :else (format "%dms" ms)))

(defn- print-timing-report
  "Print formatted timing report to stdout"
  [timings]
  (let [sorted (sort-by :duration-ms timings)
        total-ms (reduce + (map :duration-ms timings))
        test-count (count timings)
        avg-ms (if (pos? test-count) (/ total-ms test-count) 0)]
    (println)
    (println "E2E Test Timing Report")
    (println "======================")
    (println (format "Tests: %d | Total: %s | Average: %s"
                     test-count
                     (format-duration total-ms)
                     (format-duration (long avg-ms))))
    (println)
    (println "Tests sorted by duration (fastest first):")
    (println (str (apply str (repeat 60 "-"))))
    (doseq [{:keys [name duration-ms]} sorted]
      (println (format "%-7s  %s"
                       (format-duration duration-ms)
                       name)))
    (println (str (apply str (repeat 60 "-"))))
    (println)
    (println "Slowest 10 tests:")
    (doseq [{:keys [name file duration-ms]} (take-last 10 sorted)]
      (println (format "  %-7s  %s (%s)"
                       (format-duration duration-ms)
                       name
                       file)))))

;; ============================================================
;; E2E Testing
;; ============================================================

(def ^:private spinner-frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- with-spinner
  "Run a function while displaying an animated spinner with message.
   Clears the spinner line when done."
  [message f]
  (let [stop? (atom false)
        spinner-thread (Thread.
                        (fn []
                          (loop [i 0]
                            (when-not @stop?
                              (print (str "\r" (nth spinner-frames (mod i (count spinner-frames))) " " message))
                              (flush)
                              (Thread/sleep 80)
                              (recur (inc i))))))]
    (.start spinner-thread)
    (try
      (f)
      (finally
        (reset! stop? true)
        (.join spinner-thread 200)
        ;; Clear the spinner line
        (print (str "\r" (apply str (repeat (+ 3 (count message)) " ")) "\r"))
        (flush)))))

(defn- run-docker-shard
  "Run Docker container with Playwright's native sharding.
   Returns map with process and writer for cleanup."
  [shard-idx n-shards log-file extra-args]
  (let [writer (io/writer log-file)
        cmd (into ["docker" "run" "--rm" "epupp-e2e"
                   (str "--shard=" (inc shard-idx) "/" n-shards)]
                  extra-args)]
    (.write writer (str "\n=== Shard " (inc shard-idx) "/" n-shards " ===\n\n"))
    (.flush writer)
    {:process (p/process cmd {:out writer :err writer})
     :writer writer}))

(defn- strip-ansi-codes
  "Remove ANSI escape sequences from text (colors, formatting, etc.)"
  [text]
  (str/replace text #"\x1b\[[0-9;]*m" ""))

(defn- parse-playwright-summary
  "Parse Playwright summary from log output.
   Returns {:passed N :failed N :skipped N} or nil if not found.
   Handles both formats:
     Combined: '10 passed, 2 failed (5.2s)'
     Separate: '1 failed' on one line, '2 passed (3.0s)' on another"
  [log-content]
  (let [;; Strip ANSI color codes that Playwright adds
        clean-content (strip-ansi-codes log-content)
        ;; Separate patterns for counts on their own lines
        passed-pattern #"(?m)^\s*(\d+)\s+passed\s*(?:\([^)]+\))?\s*$"
        failed-pattern #"(?m)^\s*(\d+)\s+failed\s*$"
        skipped-pattern #"(?m)^\s*(\d+)\s+skipped\s*$"
        flaky-pattern #"(?m)^\s*(\d+)\s+flaky\s*$"
        ;; Always check separate patterns (handles both combined and separate formats)
        passed-match (re-find passed-pattern clean-content)
        failed-match (re-find failed-pattern clean-content)
        skipped-match (re-find skipped-pattern clean-content)
        flaky-match (re-find flaky-pattern clean-content)]
    (when passed-match
      {:passed (parse-long (nth passed-match 1))
       :failed (if failed-match (parse-long (nth failed-match 1)) 0)
       :skipped (if skipped-match (parse-long (nth skipped-match 1)) 0)
       :flaky (if flaky-match (parse-long (nth flaky-match 1)) 0)})))

(defn- count-test-files-in-log
  "Count unique test files mentioned in Playwright output."
  [log-content]
  (let [;; Match file references like "build/e2e/popup_core_test.mjs:123:1" or "build/e2e/repl_ui_spec.mjs:123:1"
        file-pattern #"build/e2e/([a-z_]+(?:_test|_spec)\.mjs):\d+:\d+"
        matches (re-seq file-pattern log-content)]
    (count (distinct (map second matches)))))

(defn- aggregate-shard-results
  "Aggregate results from all shard log files.
   Returns {:files N :total N :passed N :failed N}."
  [log-files]
  (let [results (for [log-file log-files
                      :let [content (slurp log-file)
                            summary (parse-playwright-summary content)
                            files (count-test-files-in-log content)]
                      :when summary]
                  (assoc summary :files files))
        total-passed (reduce + (map :passed results))
        total-failed (reduce + (map :failed results))
        total-skipped (reduce + (map :skipped results))
        total-flaky (reduce + (map :flaky results))
        total-files (reduce + (map :files results))]
    {:files total-files
     :total (+ total-passed total-failed)
     :passed total-passed
     :failed total-failed
     :skipped total-skipped
     :flaky total-flaky}))

(defn- print-test-summary
  "Print a summary of test results.
   When failed-override is provided, uses it instead of the parsed :failed count.
   This handles cases where shards crash without producing a parseable summary."
  [{:keys [files total passed failed skipped flaky]} & {:keys [failed-override]}]
  (let [actual-failed (or failed-override failed)]
    (println)
    (println (format "Files:    %3d" files))
    (println (format "Total:    %3d tests" total))
    (println (format "  Passed: %3d" passed))
    (println (format "  Failed: %3d%s" actual-failed
                     (if (and failed-override (not= failed-override failed))
                       (str " (" failed-override " shard(s) failed)")
                       "")))
    (println (format "Flaky:    %3d" flaky))
    (println (format "Skipped:  %3d" skipped))
    (if (zero? actual-failed)
      (println "Status:  ALL TESTS PASSED")
      (println "Status:  SOME TESTS FAILED"))))

(defn- run-build-step!
  "Run a build command, capturing output. Returns result map.
   Throws with output on failure."
  [cmd]
  (let [result (p/shell {:out :string :err :string :continue true} cmd)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Build failed: " cmd)
                      {:cmd cmd
                       :exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))
    result))

(defn- build-e2e!
  "Build E2E tests and Docker image.
   Suppresses build output unless failure. Exits on failure."
  []
  (try
    (with-spinner "Building tests and Docker image..."
      (fn []
        (run-build-step! "bb build:test")
        (run-build-step! "bb test:e2e:compile")
        (run-build-step! "docker build --platform linux/arm64 -f Dockerfile.e2e -t epupp-e2e .")))
    (catch clojure.lang.ExceptionInfo e
      (println "\n\n❌ Build failed!")
      (let [{:keys [cmd out err]} (ex-data e)]
        (println (str "Command: " cmd))
        (when (seq out)
          (println "\nStdout:")
          (println out))
        (when (seq err)
          (println "\nStderr:")
          (println err)))
      (System/exit 1))))

(defn- rotate-e2e-artifacts!
  "Rotate E2E output artifacts into .tmp/e2e-history/ before a new run.
   Shifts existing backups (1 -> 2, 2 -> 3, etc.) and moves current
   e2e-output.txt and e2e-shards/ into slot 1. Deletes anything beyond
   history-count."
  [history-count]
  (let [output-file e2e-output-file
        shard-dir (str e2e-tmp-dir "/e2e-shards")
        has-output? (fs/exists? output-file)
        has-shards? (fs/exists? shard-dir)]
    (when (or has-output? has-shards?)
      (fs/create-dirs e2e-history-dir)
      ;; Delete oldest slot if at capacity
      (let [oldest-output (str e2e-history-dir "/e2e-output-" history-count ".txt")
            oldest-shards (str e2e-history-dir "/e2e-shards-" history-count)]
        (when (fs/exists? oldest-output) (fs/delete oldest-output))
        (when (fs/exists? oldest-shards) (fs/delete-tree oldest-shards)))
      ;; Shift existing backups: N-1 -> N, ..., 2 -> 3, 1 -> 2
      (doseq [i (range history-count 1 -1)]
        (let [src-output (str e2e-history-dir "/e2e-output-" (dec i) ".txt")
              dst-output (str e2e-history-dir "/e2e-output-" i ".txt")
              src-shards (str e2e-history-dir "/e2e-shards-" (dec i))
              dst-shards (str e2e-history-dir "/e2e-shards-" i)]
          (when (fs/exists? src-output) (fs/move src-output dst-output))
          (when (fs/exists? src-shards) (fs/move src-shards dst-shards))))
      ;; Move current artifacts into slot 1
      (when has-output?
        (fs/move output-file (str e2e-history-dir "/e2e-output-1.txt")))
      (when has-shards?
        (fs/move shard-dir (str e2e-history-dir "/e2e-shards-1"))))))

(defn- run-e2e-serial!
  "Run E2E tests sequentially in a single Docker container."
  [extra-args {:keys [build?] :or {build? true}}]
  (when build?
    (build-e2e!))
  (println "Running tests (serial)...")
  (when (seq extra-args)
    (println (str "  Extra Playwright args: " (str/join " " extra-args))))
  (let [result (apply p/shell {:continue true} "docker" "run" "--rm" "epupp-e2e" extra-args)]
    (:exit result)))

(defn- run-e2e-parallel!
  "Run E2E tests in parallel Docker containers using Playwright's native sharding."
  [n-shards extra-args {:keys [build?] :or {build? true}}]
  ;; Build phase (if requested)
  (when build?
    (build-e2e!))

  ;; Run shards in parallel using Playwright's native sharding
  (println (str "Running " n-shards " parallel shards..."))
  (when (seq extra-args)
    (println (str "  Extra Playwright args: " (str/join " " extra-args))))
  (ensure-tmp-dir!)
  (let [shard-dir (str e2e-tmp-dir "/e2e-shards")
        _ (do (fs/delete-tree shard-dir) (fs/create-dirs shard-dir))
        start-time (System/currentTimeMillis)

        shards (doall
                (for [idx (range n-shards)]
                  (let [log-file (str shard-dir "/shard-" idx ".log")
                        {:keys [process writer]} (run-docker-shard idx n-shards log-file extra-args)]
                    {:idx idx
                     :process process
                     :writer writer
                     :log-file log-file
                     :done? (atom false)
                     :exit-code (atom nil)})))

        ;; Poll for completion - report as they finish
        _ (loop []
            (let [still-running (filter #(not @(:done? %)) shards)]
              (when (seq still-running)
                (doseq [{:keys [idx process writer done? exit-code]} still-running]
                  (let [proc (:proc process)
                        alive? (.isAlive proc)]
                    (when-not alive?
                      (let [exit (.exitValue proc)
                            elapsed-s (/ (- (System/currentTimeMillis) start-time) 1000.0)]
                        ;; Close writer to flush output before we read the log
                        (.close writer)
                        (reset! exit-code exit)
                        (reset! done? true)
                        (println (format "  Shard %d/%d finished at %.1fs (exit %d)"
                                         (inc idx) n-shards elapsed-s exit))))))
                (Thread/sleep 100)
                (recur))))

        results (map (fn [{:keys [idx exit-code log-file]}]
                       {:idx idx :exit @exit-code :log-file log-file})
                     shards)
        elapsed-ms (- (System/currentTimeMillis) start-time)
        failed (filter #(not= 0 (:exit %)) results)
        log-files (map :log-file results)
        summary (aggregate-shard-results log-files)
        failed-count (count failed)]

    (println)
    (println (str "Completed " n-shards " shards in " (format "%.1fs" (/ elapsed-ms 1000.0))))

    ;; Always print test summary - use failed shard count as override when shards crashed
    (print-test-summary summary :failed-override (when (pos? failed-count) failed-count))

    ;; Write combined shard output for tool consumption
    (spit e2e-output-file (str/join "\n" (map slurp log-files)))

    (println "Full test output:" e2e-output-file)
    (println "  Shards:" shard-dir)
    (println "  Previous runs:" e2e-history-dir)
    (if (seq failed) 1 0)))

; Playwright's stupid sharding will make it vary a lot what n-shards is the best
(def ^:private default-n-shards 12)

(defn ^:export run-e2e!
  "Run E2E tests in Docker. Parallel by default, --serial to disable this (but why would you?).

   Options:
     --shards N   Number of parallel shards (default: 13)
     --serial     Run sequentially (very seldom needed)
     --history N  Number of past runs to keep in .tmp/e2e-history/ (default: 10)

   Use -- to separate bb options from Playwright options:
     bb test:e2e -- --grep \"popup\""
  [args]
  (let [{:keys [args opts]} (cli/parse-args args {:coerce {:shards :int :history :int}
                                                  :alias {:s :serial}})
        args (into [(str "--retries=" default-retries)] args)
        serial? (:serial opts)
        n-shards (or (:shards opts) default-n-shards)
        history-count (or (:history opts) default-history-count)]
    (rotate-e2e-artifacts! history-count)
    (if serial?
      (let [exit-code (run-e2e-serial! args {:build? true})]
        (System/exit exit-code))
      (let [exit-code (run-e2e-parallel! n-shards args {:build? true})]
        (System/exit exit-code)))))

(defn- extract-json-from-output
  "Extract JSON object from mixed output that may have log prefixes.
   Finds the first { and parses from there."
  [output]
  (when-let [json-start (str/index-of output "{")]
    (subs output json-start)))

(defn e2e-timing-report!
  "Run E2E tests in Docker with JSON reporter and print timing report.
   Sorted fastest-first so you can tail for slowest tests."
  [_args]
  ;; Build phase with spinner (output suppressed unless failure)
  (try
    (with-spinner "Building tests and Docker image..."
      (fn []
        (run-build-step! "bb build:test")
        (run-build-step! "bb test:e2e:compile")
        (run-build-step! "docker build --platform linux/arm64 -f Dockerfile.e2e -t epupp-e2e .")))
    (catch clojure.lang.ExceptionInfo e
      (println "\n\n❌ Build failed!")
      (let [{:keys [cmd out err]} (ex-data e)]
        (println (str "Command: " cmd))
        (when (seq out)
          (println "stdout:")
          (println out))
        (when (seq err)
          (println "stderr:")
          (println err)))
      (System/exit 1)))
  ;; Run tests with spinner (output captured for JSON parsing)
  (let [result (atom nil)]
    (with-spinner "Running E2E tests (collecting timing data)..."
      #(reset! result (p/shell {:out :string :err :string :continue true}
                               "docker" "run" "--rm" "epupp-e2e"
                               "--reporter=json")))
    (let [{:keys [exit out]} @result
          json-str (extract-json-from-output out)]
      (if (and (zero? exit) json-str)
        (let [json-data (json/read-str json-str :key-fn keyword)
              timings (extract-test-timings json-data)]
          (print-timing-report timings))
        (do
          (println "Tests failed or no JSON output - cannot generate timing report")
          (when-not (str/blank? out)
            (println "Output preview:")
            (println (subs out 0 (min 500 (count out)))))
          (println "Run 'bb test:e2e' to see full test output")
          (System/exit (if (zero? exit) 1 exit)))))))
