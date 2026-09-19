(ns oscope.embedded-profile-test-runner
  (:require [clojure.test :as test]
            [oscope.embedded-profile-test]
            [oscope.time-provider-native-fixture-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.embedded-profile-test
                        'oscope.time-provider-native-fixture-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
