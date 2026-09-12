(ns oscope.langfuse-profile-test
  "Real-loopback proof for the Langfuse OTLP profile and one SDK owner."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [jolt.http.server :as http]
            [oscope.langfuse-gate :as gate]
            [oscope.otlp :as oscope-otlp]
            [otel.sdk :as sdk]
            [otel.sdk.export :as export]))

(defn- capture-handler [requests]
  (fn [request]
    (let [parsed (oscope-otlp/parse-json-body request oscope-otlp/max-body-bytes)]
      (swap! requests conj
             {:method (:request-method request)
              :uri (:uri request)
              :headers (gate/normalized-headers (:headers request))
              :payload (json/read-str
                        (json/write-str (:value parsed)) :key-fn keyword)})
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body "{}"})))

(defn- wire-spans-by-name [payload]
  (into {}
        (for [resource-spans (:resourceSpans payload)
              scope-spans (:scopeSpans resource-spans)
              span (:spans scope-spans)]
          [(:name span) span])))

(defn- wire-attributes [span]
  (into {}
        (map (fn [{:keys [key value]}]
               [key (or (:stringValue value) (:intValue value)
                        (:boolValue value) (:doubleValue value))]))
        (:attributes span)))

(deftest standalone-profile-uses-real-http-and-preserves-canonical-spans
  (let [requests (atom [])
        local-batches (atom [])
        listener (http/run-server (capture-handler requests)
                                  :port 0 :server-name "127.0.0.1"
                                  :reuse-address? true)]
    (try
      (let [remote-url (str "http://127.0.0.1:" (:port listener)
                            "/api/public/otel/v1/traces")
            runtime (gate/dual-sdk!
                     {:local-exporter (gate/memory-exporter local-batches)
                      :remote-url remote-url
                      :remote-headers
                      {"Authorization" "Basic local-test-credential"
                       "x-langfuse-ingestion-version" "4"}})]
        (try
          (let [expected (gate/emit-nested-trace!)]
            (is (true? (sdk/force-flush! (:handle runtime))))
            (is (= {:local {:ok? true} :remote {:ok? true}}
                   (export/force-flush-pipelines! (:pipelines runtime))))
            (is (= 1 (count @requests)))
            (let [{:keys [method uri headers payload]} (first @requests)
                  local (gate/span-identities (mapcat identity @local-batches))
                  remote (gate/wire-span-identities payload)]
              (is (= :post method))
              (is (= "/api/public/otel/v1/traces" uri))
              (is (= "Basic local-test-credential"
                     (get headers "authorization")))
              (is (= "4" (get headers "x-langfuse-ingestion-version")))
              (is (= "application/json" (get headers "content-type")))
              (is (= local remote))
              (is (= #{{:trace-id (:trace-id expected)
                        :span-id (:root-span-id expected)
                        :parent-span-id "" :name gate/root-name}
                       {:trace-id (:trace-id expected)
                        :span-id (:child-span-id expected)
                        :parent-span-id (:root-span-id expected)
                        :name gate/child-name}}
                     local))
              (let [by-name (wire-spans-by-name payload)]
                (is (= {"langfuse.observation.type" "span"
                        "langfuse.trace.name" "oscope-langfuse-qualification"
                        "langfuse.observation.input" "qualification-input"
                        "langfuse.observation.output" "qualification-output"}
                       (wire-attributes (get by-name gate/root-name))))
                (is (= {"langfuse.observation.type" "generation"
                        "langfuse.observation.model.name" "qualification-model"
                        "langfuse.observation.input" "generation-input"
                        "langfuse.observation.output" "generation-output"}
                       (wire-attributes (get by-name gate/child-name)))))
              (is (= {:local {:queue-size 0 :attempted-span-count 2
                              :exported-span-count 2 :failed-span-count 0
                              :dropped-count 0}
                      :remote {:queue-size 0 :attempted-span-count 2
                               :exported-span-count 2 :failed-span-count 0
                               :dropped-count 0}}
                     (export/pipeline-stats (:pipelines runtime))))))
          (finally
            (is (true? (sdk/shutdown! (:handle runtime)))))))
      (finally
        (http/stop-server listener)))))
