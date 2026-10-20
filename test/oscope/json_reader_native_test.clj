(ns oscope.json-reader-native-test
  "Optional component admission/parity gate; not native chDB delivery proof."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [jdbc.core :as jdbc]
            [jolt.codec.reader.experimental :as reader]
            [oscope.otlp :as otlp]
            [oscope.server :as server]))

(deftest actual-published-component-parses-with-configured-value-parity
  (is (str/includes? (str (io/resource "jolt/codec/reader/experimental.clj"))
                    "7ab2bedcb2e1845efdf6ed5c55ae9d2164338a8d"))
  (let [read (otlp/open-json-reader :experimental-stock)]
    (doseq [source ["false" "null" "42" "1.25" "9223372036854775808"
                    "{\"attributes\":{\"active\":false,\"name\":\"\\ud83d\\ude80\"}}"
                    "{\"a\":1,\"a\":2}"
                    (str "\"escaped\\nraw" (char 1) "control\"")]]
      (is (= (json/read-str source) (read source))))))

(deftest genuine-constructor-runs-before-jdbc-and-errors-stay-owned
  (let [events (atom []) open reader/open-reader
        sentinel (ex-info "JDBC admission sentinel" {})
        error
        (with-redefs [reader/open-reader (fn [options]
                                          (swap! events conj :reader)
                                          (open options))
                      jdbc/connection (fn [& _] (swap! events conj :jdbc) (throw sentinel))]
          (try (server/start! {:port 0 :json-reader :experimental-stock})
               (catch Throwable e e)))]
    (is (identical? sentinel error))
    (is (= [:reader :jdbc] @events))))

(defn -main [& _]
  (let [result (run-tests 'oscope.json-reader-native-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
