(ns oscope.json-reader-legacy-probe
  "Actual old-component admission control, with no mocked reader capability."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jdbc.core :as jdbc]
            [oscope.otlp :as otlp]
            [oscope.server :as server]))

(defn -main [& _]
  (assert (str/includes? (str (io/resource "jolt/codec/experimental.clj"))
                        "98e93e2a6726c435d9b55ada9a088c2dbf5b1c99"))
  (assert (nil? (io/resource "jolt/codec/reader/experimental.clj")))
  (let [calls (atom 0)
        error (with-redefs [jdbc/connection (fn [& _] (swap! calls inc))]
                (try (server/start! {:port 0 :json-reader :experimental-stock})
                     (catch Throwable e e)))]
    (assert (= ::otlp/json-reader-unavailable (:type (ex-data error))))
    (assert (nil? (ex-cause error)))
    (assert (zero? @calls))
    (println :old-reader-component-rejected-before-jdbc)))
