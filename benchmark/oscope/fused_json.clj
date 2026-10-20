(ns oscope.fused-json
  "Benchmark-only scalar scanner; no global helper or production registration."
  (:require [jolt.scheme :as scheme]))

(defn open-finder []
  (scheme/eval-string
    "(lambda (s start)
       (let ((n (string-length s)))
         (let loop ((i start))
           (if (fx>= i n) -1
             (case (char->integer (string-ref s i))
               ((34 92) i)
               (else (loop (fx+ i 1))))))))"))
