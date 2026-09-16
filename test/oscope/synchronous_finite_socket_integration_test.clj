(ns oscope.synchronous-finite-socket-integration-test
  "Actual SDK metric collection, local OTLP socket ingestion and native readback."
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.string :as str]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [oscope.server :as server]
            [otel.metrics :as metrics]
            [otel.sdk :as sdk]
            [otel.sdk.metrics :as sdk-metrics]))

(defn- retire-owned! [first-failure owned operation confirmed? phase]
  (when-let [handle @owned]
    (try
      (if (confirmed? (operation handle))
        (reset! owned nil)
        (throw (ex-info "owned fixture retirement unconfirmed" {})))
      (catch Throwable error
        ;; Retain the first Throwable privately; report only controlled phases.
        (compare-and-set! first-failure nil {:error error :phase phase})
        (is false "owned fixture retirement failed")))))

(deftest retirement-helper-preserves-primary-and-accounts-for-ownership
  (let [events (atom [])
        original (ex-info "controlled primary" {})
        failure (atom {:error original :phase :fixture-body})
        attempted (atom [])
        sdk-owner (atom :sdk)
        server-owner (atom :server)
        reader-owner (atom :reader)
        secondary (ex-info "controlled secondary" {})]
    ;; Only synchronous helper-generated expected failures are intercepted.
    ;; All outcome assertions run outside this scope using the normal reporter.
    (with-redefs [test/report #(swap! events conj %)]
      (retire-owned! failure sdk-owner
                     #(do (swap! attempted conj %) false) true? :sdk-shutdown)
      (retire-owned! failure server-owner
                     #(do (swap! attempted conj %) false)
                     #(= {:status :closed :phase :closed} %) :server-stop)
      (retire-owned! failure sdk-owner
                     #(do (swap! attempted conj %) (throw secondary)) true? :sdk-shutdown)
      (retire-owned! failure server-owner
                     #(do (swap! attempted conj %) (throw secondary))
                     #(= {:status :closed :phase :closed} %) :server-stop)
      (retire-owned! failure reader-owner #(do (swap! attempted conj %) true) true? :reader-close))
    (is (identical? original (:error @failure)))
    (is (= :fixture-body (:phase @failure)))
    (is (= [:sdk :server :sdk :server :reader] @attempted))
    (is (= :sdk @sdk-owner))
    (is (= :server @server-owner))
    (is (nil? @reader-owner))
    (is (= 4 (count @events)))
    (is (every? #(and (= :fail (:type %))
                      (= "owned fixture retirement failed" (:message %))) @events))))

(deftest synchronous-finite-inputs-cross-local-socket-without-poisoning-series
  (let [db-spec {:vendor "chdb" :name ":memory:"
                 :database (str "finite_socket_"
                                (str/replace (str (java.util.UUID/randomUUID)) "-" "_"))}
        lifecycle (server/start! {:port 0 :db-spec db-spec})
        owner (atom nil)
        reader-owner (atom nil)
        server-owner (atom lifecycle)
        first-failure (atom nil)]
    (try
      (let [endpoint (str "http://127.0.0.1:" (:port lifecycle))
            handle (sdk/init! {:exporter :otlp :processor :simple
                               :traces-url (str endpoint "/v1/traces")
                               :metrics-url (str endpoint "/v1/metrics")
                               :timeout-ms 2000 :metric-interval-ms 60000
                               :runtime-metrics? false :logs? false
                               :bridge-logging? false
                               :service-name "finite-socket-fixture"})]
        (reset! owner handle)
        (is (some? (:meter-provider handle)))
        (is (some? (:reader handle)))
        (let [meter (sdk/meter "finite-socket-fixture")
              counter (metrics/counter meter "fixture.counter")
              signed (metrics/up-down-counter meter "fixture.signed")
              attrs {"series" "kept" "typed.false" false "typed.zero" 0}
              fresh (assoc attrs "series" "fresh")]
          (is (identical? counter (metrics/add! counter 5 attrs)))
          (is (identical? signed (metrics/add-delta! signed -7 attrs)))
          (doseq [v [##NaN ##Inf ##-Inf] a [attrs fresh]]
            (is (identical? counter (metrics/add! counter v a)))
            (is (identical? signed (metrics/add-delta! signed v a))))
          (is (identical? counter (metrics/add! counter -1 fresh)))
          ;; SDK collection preserves native attribute types; metrics storage's
          ;; established generic compatibility map uses their readable strings.
          (let [points (mapcat :data-points
                              (mapcat :metrics
                                      (sdk-metrics/collect! (:meter-provider handle))))
                finite? (fn [point]
                          (let [v (:value point)]
                            (and (number? v)
                                 (let [d (double v)]
                                   (and (== d d) (not= d ##Inf) (not= d ##-Inf))))))
                poisoned-kept (count (filter #(and (= "kept" (get-in % [:attributes "series"]))
                                                   (not (finite? %))) points))
                fresh-count (count (filter #(= "fresh" (get-in % [:attributes "series"])) points))]
            ;; Fixed scalar witnesses discriminate admission poisoning from an
            ;; unrelated constructor/transport failure, without dumping values.
            (if (<= (count points) 4)
              (println :finite-socket-sdk :points (count points)
                       :all-finite (every? finite? points)
                       :poisoned-kept poisoned-kept :fresh fresh-count)
              (println :finite-socket-sdk :unexpected-point-count))
            (is (= 2 (count points)))
            (is (every? #(= attrs (:attributes %)) points)))
          (is (true? (sdk/force-flush! handle)))
          (let [reader (jdbc/connection db-spec)]
            (reset! reader-owner reader)
            (is (= (:database db-spec)
                   (:database (jdbc/fetch-one reader "select currentDatabase() database"))))
            (let [rows (jdbc/fetch reader
                           ["select MetricName, Value, Attributes, IsMonotonic from otel_metrics_sum where ServiceName=? order by MetricName"
                            "finite-socket-fixture"])]
                (is (= ["fixture.counter" "fixture.signed"] (mapv :metricname rows)))
                (is (every? #(number? (:value %)) rows))
                (is (= [5.0 -7.0] (mapv #(double (:value %)) rows)))
                (is (= [true false] (mapv :ismonotonic rows)))
                (is (every? #(= {"series" "kept" "typed.false" "false" "typed.zero" "0"}
                                (:attributes %)) rows))))))
      (catch Throwable error
        (compare-and-set! first-failure nil {:error error :phase :fixture-body}))
      (finally
        ;; Attempt each independent retirement even if another fails. Handles
        ;; remain owned until their public completion contract confirms closure.
        (retire-owned! first-failure reader-owner #(do (.close %) true) true? :reader-close)
        (retire-owned! first-failure owner sdk/shutdown! true? :sdk-shutdown)
        (retire-owned! first-failure server-owner server/stop!
                       #(= {:status :closed :phase :closed} %) :server-stop)))
    (when @first-failure
      (println :finite-socket-fixture-failed (:phase @first-failure)))
    (is (true? (nil? @first-failure)) "owned fixture body and retirements succeeded")
    (is (every? nil? [@reader-owner @owner @server-owner]))))
