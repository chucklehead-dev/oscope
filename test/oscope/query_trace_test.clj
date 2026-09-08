(ns oscope.query-trace-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [hegel.trace :as trace]
            [jdbc.core :as jdbc]
            [oscope.query.expression :as expression]
            [oscope.query.expression.chdb :as expression-chdb]
            [oscope.query.trace :as query-trace]))

(def ^:private now 2000000000000000000)

(defn- query [alias filter-value]
  {:signal :spans :window :15m :bucket :none
   :group-by [:service-name]
   :filters [{:field :status-code :op :eq :value filter-value}]
   :series [{:as alias :op :count}]
   :calculations [] :limit 20})

(defn- capture [f]
  (let [events (atom [])]
    {:result (query-trace/call-with-event-sink #(swap! events conj %) f)
     :events @events}))

(defn- valid-terminal? [operation event]
  (let [{:keys [phase value]} event]
    (and (= 1 (:oscope.query-trace/version event))
         (= (if (= :invoke phase)
              #{:oscope.query-trace/version :seq :operation-id
                :parent-operation-id :phase :operation}
              #{:oscope.query-trace/version :seq :operation-id :phase :value})
            (set (keys event)))
         (or (= :invoke phase)
             (case operation
               :oscope.plotje-query/compile
               (if (= :return phase)
                 (and (= :compiled (:outcome value))
                      (= #{:outcome :signal :window :bucket :group-count
                           :global-filter-count :series-count :series-filter-count
                           :calculation-count :output-count}
                         (set (keys value)))
                      (contains? #{:spans :logs :metrics} (:signal value))
                      (contains? #{:15m :1h :6h :24h} (:window value))
                      (contains? #{:none :1m :5m :15m :1h} (:bucket value))
                      (every? #(and (integer? %) (<= 0 %))
                              ((juxt :group-count :global-filter-count
                                     :series-count :series-filter-count
                                     :calculation-count :output-count) value)))
                 (and (= :throw phase) (= {:outcome :rejected} value)))

               :oscope.plotje-query/execute
               (if (= :return phase)
                 (= {:outcome :succeeded} value)
                 (and (= :throw phase)
                      (= #{:outcome :category} (set (keys value)))
                      (= :failed (:outcome value))
                      (contains? #{:expression :backend} (:category value))))

               false)))))

(defn- closed-outcomes? [events]
  (let [operations (into {} (keep #(when (= :invoke (:phase %))
                                      [(:operation-id %) (:operation %)]))
                         events)]
    (every? #(valid-terminal? (get operations (:operation-id %)) %) events)))

(def ^:private trace-rules
  [(trace/contiguous-sequence :plotje-query-contiguous)
   (trace/closed-lifecycles :plotje-query-closed)
   (trace/synchronous-parentage :plotje-query-nested)
   (trace/rule :plotje-query-closed-outcomes closed-outcomes?)])

(deftest execute-emits-one-nested-redacted-lifecycle
  (let [private-alias :private-customer-alias-7f2c91
        private-filter "private-filter-value-7f2c91"
        private-row "private-result-row-7f2c91"
        {:keys [result events]}
        (with-redefs [jdbc/fetch
                      (fn [_ _ _]
                        [{:dimension0 private-row :series0 3}])]
          (capture #(expression-chdb/execute!
                     ::connection (query private-alias private-filter) now)))]
    (is (= [{:service-name private-row private-alias 3}] result))
    (is (= [:oscope.plotje-query/execute :oscope.plotje-query/compile nil nil]
           (mapv :operation events)))
    (is (= [:invoke :invoke :return :return] (mapv :phase events)))
    (is (= [nil 0 nil nil] (mapv :parent-operation-id events)))
    (is (= [0 1 1 0] (mapv :operation-id events)))
    (is (= events (trace/check! events trace-rules {:max-events 4})))
    (let [printed (pr-str events)]
      (doseq [secret [(name private-alias) private-filter private-row
                      "SELECT" "otel_traces"]]
        (is (not (str/includes? printed secret)))))))

(deftest rejection-and-backend-failure-events-are-negative-privacy-controls
  (let [private-sql "SELECT private-sql-credential-7f2c91"
        invalid-events (atom [])
        invalid-error
        (try
          (query-trace/call-with-event-sink
           #(swap! invalid-events conj %)
           #(expression/compile-query
             (assoc (query :safe "private-filter-7f2c91") :sql private-sql)
             now))
          nil
          (catch Throwable error error))
        backend-events (atom [])
        backend-error (ex-info "private-backend-message-7f2c91"
                               {:credential "private-password-7f2c91"})
        observed-error
        (with-redefs [jdbc/fetch (fn [& _] (throw backend-error))]
          (try
            (query-trace/call-with-event-sink
             #(swap! backend-events conj %)
             #(expression-chdb/execute!
               ::connection (query :private-alias-7f2c91
                                   "private-filter-7f2c91") now))
            nil
            (catch Throwable error error)))]
    (is (some? invalid-error))
    (is (= [:invoke :throw] (mapv :phase @invalid-events)))
    (is (= {:outcome :rejected} (:value (last @invalid-events))))
    (is (identical? backend-error observed-error))
    (is (= [:invoke :invoke :return :throw] (mapv :phase @backend-events)))
    (is (= {:outcome :failed :category :backend}
           (:value (last @backend-events))))
    (doseq [secret [private-sql "private-filter-7f2c91"
                    "private-alias-7f2c91" "private-backend-message-7f2c91"
                    "private-password-7f2c91"]]
      (is (not (str/includes? (pr-str [@invalid-events @backend-events])
                              secret))))
    (is (= @invalid-events (trace/check! @invalid-events trace-rules)))
    (is (= @backend-events (trace/check! @backend-events trace-rules)))))

(deftest trace-sink-failures-do-not-change-query-results-or-errors
  (let [plan (query :requests "private")
        result (query-trace/call-with-event-sink
                (fn [_] (throw (ex-info "sink failed" {})))
                #(expression/compile-query plan now))
        failure (ex-info "application failed" {})]
    (is (= plan (:expression result)))
    (is (identical?
         failure
         (try
           (with-redefs [jdbc/fetch (fn [& _] (throw failure))]
             (query-trace/call-with-event-sink
              (fn [_] (throw (ex-info "sink failed" {})))
              #(expression-chdb/execute! ::connection plan now)))
           nil
           (catch Throwable error error))))))

(deftest generated-compiles-retain-ordered-closed-privacy-shaped-traces
  (let [result
        (h/run-test!
         {:name "oscope Plotje query semantic trace"
          :database "" :verbosity :quiet :derandomize? true :test-cases 120}
         (fn [_]
           (let [alias (keyword (str "private-alias-" (h/draw! (g/uuid))))
                 filter-value (str "private-filter-" (h/draw! (g/uuid)))
                 plan (assoc (query alias filter-value)
                             :window (h/draw! (g/sampled-from [:15m :1h :6h :24h]))
                             :bucket (h/draw! (g/sampled-from
                                               [:none :1m :5m :15m :1h])))
                 {:keys [events]} (capture #(expression/compile-query plan now))
                 printed (pr-str events)]
             (when (or (str/includes? printed (name alias))
                       (str/includes? printed filter-value))
               (throw (ex-info "private query data leaked into semantic trace"
                               {:hegel/origin "plotje-query-trace/privacy"})))
             (trace/check! events trace-rules {:max-events 2}))))]
    (is (:passed? result)
        (pr-str (select-keys result [:status :seed :failures :final :error])))
    (is (false? (:flaky? result)))))
