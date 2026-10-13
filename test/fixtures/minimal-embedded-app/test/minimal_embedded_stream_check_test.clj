(ns minimal-embedded-stream-check-test
  (:require [clojure.test :refer [deftest is]]
            [minimal-embedded-app :as ordinary]
            [jdbc.chdb.native :as native]
            [jdbc.core :as jdbc]))

;; Compile the candidate exactly where bare assert checks would disappear.
(binding [*assert* false] (require 'minimal-embedded-stream :reload))

(defn invoke-mode [mode calls]
  (let [main (ns-resolve 'minimal-embedded-stream '-main)]
    (with-redefs [native/chdb-stream-insert-n (fn [& _] nil)
                  ordinary/-main (fn [& _]
                                   (dotimes [_ calls]
                                     (native/chdb-stream-insert-n :synthetic)))]
      (with-out-str (main mode :unused :unused :unused)))))

(deftest stream-witness-is-unconditional
  (is (thrown? clojure.lang.ExceptionInfo (invoke-mode "writer" 0)))
  (is (thrown? clojure.lang.ExceptionInfo (invoke-mode "writer" 2)))
  (is (= "minimal embedded stream fixture: PASS\n" (invoke-mode "writer" 1)))
  (doseq [mode ["reader" "mutant"]]
    (is (thrown? clojure.lang.ExceptionInfo (invoke-mode mode 1)))
    (is (= "minimal embedded stream fixture: PASS\n" (invoke-mode mode 0))))
  (is (thrown? clojure.lang.ExceptionInfo (invoke-mode "invalid" 0))))

(deftest physical-readback-is-unconditional
  (let [check (ns-resolve 'minimal-embedded-stream 'typed-readback!)
        expected [{:span_name "minimal embedded fixture" :typed_value true
                   :typed_status 3 :generic_value "true"}]]
    (doseq [rows [[]
                  (assoc-in expected [0 :typed_value] false)
                  (assoc-in expected [0 :typed_status] 0)
                  (assoc-in expected [0 :generic_value] "false")]]
      (with-redefs [jdbc/fetch (fn [& _] rows)]
        (is (thrown? clojure.lang.ExceptionInfo (check :synthetic)))))
    (with-redefs [jdbc/fetch (fn [& _] expected)]
      (is (nil? (check :synthetic))))))
