(ns oscope.live-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.live :as live]
            [oscope.query :as query]
            [otel.exporter.chdb.schema :as schema]))

(def test-now 1700000001000000000)
(deftest shared-connection-is-never-owned-or-closed
  (let [migrations (atom []) connection {:shared :collector}]
    (with-redefs [schema/ensure-schema! #(swap! migrations conj %)
                  query/run (fn [_ plan]
                              [{:signal :spans :field :service-name
                                :value "api" :count 2}])]
      (let [source (live/open! {:connection connection
                                :now-fn (constantly test-now)})]
        (is (false? (:owned? source)))
        (is (= [connection] @migrations))
        (is (= [{:value "api" :count 2}] (get-in source [:screen :table :rows])))
        (live/close! source)
        (live/close! source)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source is closed"
                              ((:loader source) (:selection (:screen source)))))))))
