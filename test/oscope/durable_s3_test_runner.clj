(ns oscope.durable-s3-test-runner
  (:require [clojure.test :as test]
            [jdbc.chdb.native :as native]
            [oscope.durable-s3-integration-test]))

(defn -main [& _]
  (when-not (= :supported (:status (native/durable-capability)))
    (throw (ex-info "S3 integration requires the qualified Durable chDB ABI" {})))
  (let [result (test/run-tests 'oscope.durable-s3-integration-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "oscope Durable S3 integration failed" result)))))
