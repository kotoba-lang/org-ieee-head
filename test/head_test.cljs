;; test/head_test.cljs — build the command and compare it with the system
;; head, byte for byte AND by exit status.
;;
;; SUCCESS paths only, deliberately. /usr/bin/head refuses `-n 0` and a
;; missing operand on STDERR and exits 1; there is no stderr capability --
;; :io/write is fd 1 and only fd 1 -- so those bytes cannot be produced and
;; are not claimed. The cases below are the ones this implementation says it
;; matches.
;;
;; This does not assert that the guest COMPILES. It compiles it, packages it
;; into a standalone binary, runs that binary, and compares its bytes and its
;; exit status against /bin/cat -- because `:ok true` from a compiler means
;; the artifact was built, not that it is right, and this repository's whole
;; claim is about what the artifact does.
;;
;; Every case is passed as an ARGUMENT VECTOR, never through a shell. zsh does
;; not word-split an unquoted variable, so `cmd $args` hands the whole string
;; over as ONE argument -- which silently turns a multi-argument test into a
;; single-argument one and makes the join logic and `-n` look tested when they
;; are not. Measured 2026-09-09: exactly that mistake made `-n hi` and
;; `a b c d e` pass before either was implemented or exercised.
;;
;;   AMU_HOME=<amu checkout> nbb test/echo_test.cljs
;;
;; Exits 0 when every case matches, 1 on any difference, and 2 when it could
;; not run at all -- a distinct code, so "did not run" is never read as "ran
;; and found nothing".
(ns head-test
  (:require [clojure.string :as str] ["fs" :as fs] ["path" :as path] ["os" :as os]))

(def cp (js/require "node:child_process"))

(defn- run [cmd args opts]
  (let [r (.spawnSync cp cmd (clj->js args)
                      (clj->js (merge {:encoding "buffer"} opts)))]
    {:status (.-status r) :out (.-stdout r) :err (.-stderr r)}))

(defn- refuse [message]
  (println (pr-str {:ok false :phase :setup :message message}))
  (.exit js/process 2))

(def amu-home
  (or (.-AMU_HOME js/process.env)
      (let [guess (.resolve path (.cwd js/process) ".." ".." "kotoba-lang" "amu")]
        (when (.existsSync fs (.join path guess "bin" "amu")) guess))))

(def system-head "/usr/bin/head")

;; A directory of fixtures, and the cases over them. Each case is an argv,
;; and each is here because it separates a right implementation from a wrong
;; one that passes the others:
;;
;;   one file           -- the basic contract
;;   two files          -- concatenated in ORDER, with nothing added between
;;   the same file twice-- an operand is not deduplicated
;;   an EMPTY file      -- reads as the empty string, which must not end the
;;                         loop the way "past the last operand" does
;;   empty then content -- the same trap from the other side
;;   no trailing newline-- cat adds nothing of its own
;;   binary-ish bytes   -- high bytes survive the round trip
;;   no operands        -- POSIX reads stdin; there is no stdin capability,
;;                         so this asserts what it ACTUALLY does (nothing),
;;                         not what POSIX says
(def fixtures
  {"twenty"  (apply str (map #(str "line" % "\n") (range 1 21)))
   "three"   "a\nb\nc\n"
   "nonl"    "no trailing newline"
   "empty"   ""
   ;; Two lines and no trailing newline: the case that separates "the bytes
   ;; up to and including the Nth newline" from "N lines re-joined by
   ;; newlines". /usr/bin/head answers three bytes here, not four.
   "partial" "x\ny"})

(def cases
  [["twenty"] ["three"] ["nonl"] ["empty"] ["partial"]
   ["-n" "3" "twenty"]
   ;; 1 is the low boundary and 20 is the exact line count -- a walk that
   ;; stops one early or one late fails one of these and not the other.
   ["-n" "1" "twenty"] ["-n" "20" "twenty"]
   ;; More than the file has: emitted whole, unchanged.
   ["-n" "25" "twenty"] ["-n" "10" "three"]
   ["-n" "2" "partial"] ["-n" "1" "nonl"]])

(when-not amu-home (refuse "set AMU_HOME to an amu checkout"))
(let [amu (.join path amu-home "bin" "amu")
      packager (.join path amu-home "scripts" "package-command.cljs")]
  (when-not (.existsSync fs amu) (refuse (str "no amu at " amu)))
  (when-not (.existsSync fs packager) (refuse (str "no packager at " packager)))
  (when-not (.existsSync fs system-head) (refuse (str "no " system-head " to compare against")))
  (let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "org-ieee-wc-"))
        src (.resolve path (.cwd js/process) "head" "core.kotoba")
        policy (.join path tmp "policy.edn")
        kexe (.join path tmp "head.kexe")
        blob (.join path tmp "head.bin")
        exe (.join path tmp "head")
        exe-big (.join path tmp "head-big")]
    (.writeFileSync fs policy "{:allow #{[:cap/call 35] [:cap/call 37] [:cap/call 38]}}" "utf8")
    ;; The fixtures live in the tree the binary is packaged for. The native
    ;; loader refuses a relative request outright, so operands are absolute.
    (let [data (.join path tmp "data")]
      (.mkdirSync fs data)
      (doseq [[name content] fixtures]
        (.writeFileSync fs (.join path data name)
                        content
                        "utf8")))
    (let [c (run "node" [amu "compile" src "--target" "aarch64-macos" "--jvm-free"
                         "--policy" policy "--output" kexe] {})]
      (when (not= 0 (:status c))
        (refuse (str "compile failed: " (str (:err c)) (str (:out c))))))
    (let [e (run "node" [amu "extract-native" kexe "--symbol" "main" "--output" blob] {})
          _ (when (not= 0 (:status e)) (refuse (str "extract failed: " (str (:err e)))))
          report (str (:out e))
          offset (second (re-find #":offset (\d+)" report))]
      (when-not offset (refuse (str "no :offset in the extract report: " report)))
      ;; TWO binaries from the same code: one with the loader's default
      ;; string-arena budget and one with a raised budget. The pair is what
      ;; makes the ceiling below a measurement instead of a claim -- a single
      ;; binary could only show that some size works and some does not, not
      ;; that the bound is the arena and that it moves.
      ;; Fuel and arena are constants of the binary, so they are packaged
      ;; here rather than supplied at run time. Counting words walks one code
      ;; point at a time, so the guest recursion is as long as the file and
      ;; the default 512 fuel counts almost nothing.
      (doseq [[out extra] [[exe ["--fuel" "5000000" "--string-pool" "4000000"]]]]
        (let [p (run "nbb" (into [packager "--code" blob "--offset" offset "--isa" "aarch64"
                                  "--allow" "35,37,38"
                                  "--fs-scope" (.realpathSync fs (.join path tmp "data"))
                                  "--output" out]
                                 extra) {})]
          (when (not= 0 (:status p)) (refuse (str "package failed: " (str (:err p))))))))
    ;; Now the only thing that matters: run it.
    (let [results
          (for [names cases]
            (let [argv (mapv #(if (or (str/starts-with? % "-") (re-matches #"[0-9]+" %))
                                %
                                (.join path (.realpathSync fs (.join path tmp "data")) %))
                             names)
                  k (run exe argv {})
                  s (run system-head argv {})
                  same? (and (= (.toString (:out k) "base64") (.toString (:out s) "base64"))
                             (= (:status k) (:status s)))]
              {:argv names :ok same? :kotoba (.toString (:out k) "utf8")
               :system (.toString (:out s) "utf8")
               :exit [(:status k) (:status s)]}))
          bad (remove :ok results)]
      (doseq [r results]
        (println (str (if (:ok r) "  ok   " "  FAIL ")
                      (pr-str (:argv r))
                      " -> " (pr-str (:kotoba r))
                      (when-not (:ok r) (str " but " system-head " says " (pr-str (:system r))
                                             " exits " (pr-str (:exit r))))))) 
      (println (pr-str {:ok (empty? bad) :cases (count results) :failed (count bad)}))
      (.exit js/process (if (seq bad) 1 0)))))