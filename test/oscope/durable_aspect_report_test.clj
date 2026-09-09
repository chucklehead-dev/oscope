(ns oscope.durable-aspect-report-test
  (:require [clojure.edn :as edn]))

(def ^:private durable-seam-revision
  "4a0b82119a09fdadb08442cb5d189bdc0474ed86")

(def ^:private expected
  [[:durable/acquire 'jdbc.chdb.durable.control/acquire! 2]
   [:durable/publish-wal 'jdbc.chdb.durable.control/publish-wal-bytes! 4]
   [:durable/publish-checkpoint
    'jdbc.chdb.durable.control/publish-checkpoint-file! 4]
   [:durable/commit-reference 'jdbc.chdb.durable.control/commit-reference! 3]
   [:durable/renew 'jdbc.chdb.durable.control/renew! 4]
   [:durable/release 'jdbc.chdb.durable.control/release! 3]])

(def ^:private expected-consumers
  ['jolt.aspect-packs.chdb-durable.provider/aspect-provider
   'oscope.durable-observability/aspect-provider])

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspects (:aspects report)
        by-id (into {} (map (juxt :id identity)) aspects)]
    (when-not (= 1 (:schema report))
      (throw (ex-info "unexpected aspect report schema" {})))
    (when-not (= "jolt.aspect-ir/v1" (:weaver report))
      (throw (ex-info "unexpected aspect weaver" {})))
    (when-not (false? (:control-enabled? report))
      (throw (ex-info "Durable consumers enabled control advice" {})))
    (when-not (= (set (map first expected)) (set (keys by-id)))
      (throw (ex-info "Durable report selected wrong aspects"
                      {:actual (mapv :id aspects)})))
    (doseq [[id entry arity] expected
            :let [aspect (get by-id id)]]
      (when-not (= {:id 'io.github.chucklehead-dev/jolt-chdb
                    :version durable-seam-revision}
                   (:library aspect))
        (throw (ex-info "wrong Durable seam identity" {:aspect id})))
      (when-not (= {:entry entry :arity arity} (:match aspect))
        (throw (ex-info "wrong Durable entry selector" {:aspect id})))
      (when-not (= 1 (count (:sites aspect)))
        (throw (ex-info "Durable entry must match exactly once" {:aspect id})))
      (when-not (= expected-consumers (mapv :provider (:consumers aspect)))
        (throw (ex-info "wrong ordered Durable consumers" {:aspect id})))
      (when-not (every? #(= :args-v1 (:contract %)) (:consumers aspect))
        (throw (ex-info "wrong Durable advice contract" {:aspect id}))))
    (println "OSCOPE-CHDB-DURABLE-ASPECT-REPORT OK")))
