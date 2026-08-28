(ns oscope.otlp-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.otlp :as otlp]))

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
