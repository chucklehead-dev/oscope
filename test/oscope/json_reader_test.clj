(ns oscope.json-reader-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [oscope.config :as config]
            [oscope.config-cli :as cli]
            [oscope.durable-config-runtime :as runtime]
            [oscope.otlp :as otlp]
            [oscope.server :as server]
            [otel.otlp.http-receiver :as receiver]))

(defn failure [run] (try (run) nil (catch Throwable e e)))

(deftest default-reader-does-not-resolve-optional-components
  (with-redefs [clojure.core/require (fn [& _] (throw (ex-info "Unexpected require" {})))]
    (is (= {"a" [false nil 7]} ((otlp/open-json-reader :configured) "{\"a\":[false,null,7]}")))))

(deftest invalid-and-unavailable-readers-reject-before-jdbc
  (let [effects (atom []) original ns-resolve]
    (with-redefs [jdbc/connection (fn [& _] (swap! effects conj :jdbc))]
      (doseq [selection [nil true :unknown "PRIVATE-READER-CANARY"]]
        (let [error (failure #(server/start! {:port 0 :json-reader selection}))]
          (is (= ::otlp/invalid-json-reader (:type (ex-data error))))
          (is (not (.contains (str error) "PRIVATE-READER-CANARY")))))
      (with-redefs [clojure.core/ns-resolve
                    (fn [& args]
                      (if (= 'jolt.codec.reader.experimental (first args)) nil
                        (apply original args)))]
        (let [error (failure #(server/start! {:port 0 :json-reader :experimental-stock}))]
          (is (= ::otlp/json-reader-unavailable (:type (ex-data error))))
          (is (nil? (ex-cause error)))))
      (is (empty? @effects)))))

(deftest custom-read-is-after-utf8-byte-limit-and-preserves-result
  (let [source "{\"name\":\"🚀\"}" calls (atom [])
        read (fn [text] (swap! calls conj text) {"decoded" false})
        bytes (alength (.getBytes source "UTF-8"))]
    (is (= {:value {"decoded" false} :encoded-bytes bytes}
           (otlp/parse-json-body {:body source} bytes read)))
    (is (= [source] @calls))
    (reset! calls [])
    (is (some? (failure #(otlp/parse-json-body {:body source} (dec bytes) read))))
    (is (empty? @calls))))

(deftest handler-injects-reader-without-bypassing-durability-wrapper
  (let [events (atom [])]
    (with-redefs [receiver/handler
                  (fn [{:keys [parse-body]}]
                    (fn [request]
                      (is (= {"selected" true} (:value (parse-body request 100))))
                      (swap! events conj :exported)
                      {:status 200}))]
      (let [receive (otlp/handler ::exporter
                      {:json-read (fn [_] (swap! events conj :parsed) {"selected" true})
                       :after-success! #(do (swap! events conj :flushed) true)})]
        (is (= 200 (:status (receive {:body "{}"}))))
        (is (= [:parsed :exported :flushed] @events))))
    (is (= ::otlp/invalid-json-reader
           (:type (ex-data (failure #(otlp/handler ::exporter {:json-read nil}))))))))

(deftest configuration-selection-roundtrips-and-propagates
  (let [document {:version 2 :ingest {:json-reader :experimental-stock}
                  :storage {:type :memory}}
        resolved (config/resolve-config [[:file document]])
        encoded (config/encode (:config resolved))]
    (is (= :experimental-stock (get-in (config/parse encoded) [:ingest :json-reader])))
    (is (= :experimental-stock (:json-reader (cli/server-options resolved (fn [& _] nil)))))
    (is (not (contains? (:ingest config/defaults) :json-reader))))
  (let [resolved (config/resolve-config
                   [[:file {:version 2 :ingest {:json-reader :experimental-stock}
                            :storage {:type :durable-local :root "/not-opened"}}]])]
    (with-redefs [durable/writer-dbspec identity]
      (is (= :experimental-stock
             (:json-reader (runtime/server-options resolved {}
                             {:local-backend-fn (constantly ::backend)
                              :s3-backend-fn identity :object-backend-fn identity
                              :instance-fn (constantly "instance")})))))))

(deftest version-one-and-unknown-values-remain-closed
  (is (= [:ingest :json-reader]
         (:path (ex-data (failure #(config/resolve-config
                                    [[:file {:version 1 :ingest {:json-reader :configured}}]]))))))
  (doseq [selection [nil true :unknown "PRIVATE-READER-CANARY"]]
    (let [error (failure #(config/resolve-config
                           [[:file {:version 2 :ingest {:json-reader selection}}]]))]
      (is (= [:ingest :json-reader] (:path (ex-data error))))
      (is (not (.contains (str error) "PRIVATE-READER-CANARY"))))))

(defn -main [& _]
  (let [result (run-tests 'oscope.json-reader-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
