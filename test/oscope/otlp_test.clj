(ns oscope.otlp-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.otlp :as otlp]
            [otel.otlp.http-receiver :as receiver]))

(deftest parser-measures-and-bounds-encoded-json
  (let [body "{\"resourceSpans\":[]}"
        parsed (otlp/parse-json-body {:body body} 1024)]
    (is (= {"resourceSpans" []} (:value parsed)))
    (is (= (alength (.getBytes body "UTF-8")) (:encoded-bytes parsed))))
  (let [response ((otlp/handler ::exporter)
                  {:request-method :post :uri "/v1/traces"
                   :headers {"content-type" "application/json"
                             "content-length" (str (inc otlp/max-body-bytes))}
                   :body ""})]
    (is (= 413 (:status response)))
    (is (= "close" (get-in response [:headers "Connection"])))))

(deftest successful-receive-crosses-durability-boundary-before-acknowledgement
  (let [events (atom [])]
    (with-redefs [receiver/handler
                  (fn [_]
                    (fn [_]
                      (swap! events conj :exported)
                      {:status 200 :headers {} :body "ok"}))]
      (let [response ((otlp/handler
                       ::exporter
                       {:after-success! #(do (swap! events conj :flushed)
                                             true)})
                      {})]
        (is (= 200 (:status response)))
        (is (= [:exported :flushed] @events))))
    (with-redefs [receiver/handler (fn [_] (constantly {:status 200}))]
      (let [response ((otlp/handler
                       ::exporter
                       {:after-success! #(throw (ex-info "lost flush" {}))})
                      {})]
        (is (= 503 (:status response)))
        (is (= "close" (get-in response [:headers "Connection"])))
        (is (= "durability boundary failed\n" (:body response)))))
    (with-redefs [receiver/handler (fn [_] (constantly {:status 200}))]
      (doseq [unconfirmed [nil false {:status :unexpected}]]
        (let [response ((otlp/handler
                         ::exporter
                         {:after-success! (constantly unconfirmed)})
                        {})]
          (is (= 503 (:status response))))))))

(deftest durability-admission-covers-export-through-flush
  (let [events (atom [])
        flush-entered (promise)
        release-flush (promise)
        first-response (promise)]
    (with-redefs [receiver/handler
                  (fn [_]
                    (fn [request]
                      (swap! events conj [:export (:request-id request)])
                      {:status 200 :headers {} :body "ok"}))]
      (let [receive (otlp/handler
                     ::exporter
                     {:after-success!
                      #(do
                         (swap! events conj :flush-entered)
                         (deliver flush-entered true)
                         @release-flush
                         (swap! events conj :flush-finished)
                         true)})
            worker (Thread.
                    #(deliver first-response
                              (receive {:request-id :first})))]
        (.start worker)
        (is (= true (deref flush-entered 1000 ::timeout)))
        (let [second-response (receive {:request-id :second})]
          (is (= 429 (:status second-response)))
          (is (= "close" (get-in second-response
                                  [:headers "Connection"])))
          (is (= "durability boundary busy\n" (:body second-response))))
        (deliver release-flush true)
        (.join worker 1000)
        (is (not (.isAlive worker)))
        (is (= 200 (:status (deref first-response 1000 ::timeout))))
        (is (= [[:export :first] :flush-entered :flush-finished]
               @events))))))

(deftest failed-durability-boundary-releases-admission
  (let [attempts (atom 0)]
    (with-redefs [receiver/handler (fn [_] (constantly {:status 200}))]
      (let [receive (otlp/handler
                     ::exporter
                     {:after-success!
                      #(if (= 1 (swap! attempts inc))
                         (throw (ex-info "first flush failed" {}))
                         true)})]
        (is (= 503 (:status (receive {}))))
        (is (= 200 (:status (receive {}))))
        (is (= 2 @attempts))))))
