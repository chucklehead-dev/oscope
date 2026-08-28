(ns oscope.sample-emitter-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.host :as host]
            [oscope.sample-emitter :as emitter]))

(deftest endpoint-resolution-is-standard-and-stable
  (testing "explicit endpoints win and trailing slashes are normalized"
    (is (= "http://127.0.0.1:14318"
           (emitter/endpoint "http://127.0.0.1:14318///"))))
  (testing "the loopback oscope endpoint is the safe default"
    (with-redefs [host/getenv (constantly nil)]
      (is (= emitter/default-endpoint (emitter/endpoint nil))))))
