(ns oscope.config-property-test
  (:require [clojure.test :refer [deftest is]]
            [hegel.clojure-test :refer [with]]
            [hegel.generator :as g]
            [oscope.config :as config]))

(deftest highest-precedence-port-wins
  (with {:test-cases 200 :database "" :verbosity :quiet}
    [ports (g/vector {:size 4} (g/integer 0 65535))]
    (let [[default-port file-port environment-port cli-port] ports
          resolved (config/resolve-config
                    [[:test-default {:server {:port default-port}}]
                     [:file {:server {:port file-port}}]
                     [:environment {:server {:port environment-port}}]
                     [:cli {:server {:port cli-port}}]])]
      (is (= cli-port (get-in resolved [:config :server :port])))
      (is (= :cli (get-in resolved [:provenance [:server :port]]))))))
