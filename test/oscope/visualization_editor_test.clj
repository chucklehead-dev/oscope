(ns oscope.visualization-editor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing thrown-with-msg?]]
            [jolt.http.body :as http-body]
            [oscope.hiccup.spec :as hiccup]
            [oscope.query :as query]
            [oscope.sample :as sample]
            [oscope.ui.visualization-editor :as editor]
            [oscope.visualization.document :as document]))

(defn- thrown-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))

(defn- form [value]
  (str "spec="
       (java.net.URLEncoder/encode value "UTF-8")))

(deftest visualization-documents-are-versioned-bounded-and-self-validating
  (let [plotje (document/plotje-from-screen sample/default-screen)
        hiccup-document (document/default-hiccup)]
    (is (= 2 (:oscope.visualization-document/version plotje)))
    (is (= :ready (:status plotje)))
    (is (= [{:service-name "gateway" :count 42}
            {:service-name "checkout" :count 27}
            {:service-name "worker" :count 13}]
           (get-in plotje [:value :data])))
    (is (str/includes? (:text plotje)
                       ":source :telemetry-query"))
    (is (str/includes? (:text plotje) ":group-by [:service-name]"))
    (is (str/includes? (:text plotje) ":series [{:as :count, :op :count}]"))
    (is (not (str/includes? (:text plotje) "gateway")))
    (is (= :ready (:status hiccup-document)))
    (is (= hiccup-document (document/validate-document hiccup-document)))
    (is (:oscope.visualization-document/error
         (thrown-data #(document/validate-document
                        (assoc plotje :status :invalid)))))
    (is (= :invalid (:status (document/prepare :plotje "{:layers :wrong}"))))
    (is (= :invalid (:status (document/prepare :hiccup "[:script \"no\"]"))))
    (is (= 413
           (:status
            (thrown-data
             #(document/prepare
               :hiccup (apply str (repeat (inc hiccup/max-spec-chars) "x")))))))
    (is (:oscope.visualization-document/error
         (thrown-data #(document/prepare :javascript "alert(1)"))))))

(deftest visualization-documents-bound-server-owned-data-even-for-literal-specs
  (let [literal "{:data [{:x 1 :y 2}] :layers [{:mark :point :x :x :y :y}]}"
        rows (vec (repeat 513 {:x 1 :y 2}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must contain from 1 to 512 rows"
                          (document/prepare :plotje literal {:unused rows})))))

(deftest metric-series-editor-names-fields-without-copying-result-points
  (let [screen (sample/screen-for-selection
                query/default-metric-series-selection)
        document (document/plotje-from-screen screen)
        text (:text document)]
    (is (str/includes? text
                       ":select [:bucket-start-unix-nano :service-name :avg :p95]"))
    (is (not (str/includes? text ":p95 5.8")))
    (is (not (str/includes? text "checkout")))
    (is (= (:chart screen) (:value document)))))

(deftest counter-editor-retains-recipe-provenance-without-returned-points
  (let [screen (sample/screen-for-selection
                query/default-counter-series-selection)
        document (document/plotje-from-screen screen)
        text (:text document)]
    (is (str/includes? text ":source :counter-query"))
    (is (str/includes? text ":mode :counter-series"))
    (is (str/includes? text ":metric-kind :sum"))
    (is (str/includes? text ":temporality :cumulative"))
    (is (str/includes? text ":monotonic? true"))
    (is (str/includes? text ":aggregates [:increase :rate]"))
    (is (str/includes? text ":interval-count :reset-count :observed-duration-nanos"))
    (is (not (str/includes? text ":increase 42.0")))
    (is (not (str/includes? text "checkout")))
    (is (= (:chart screen) (:value document)))))

(deftest cumulative-histogram-editor-retains-descriptors-without-returned-points
  (let [screen (sample/screen-for-selection
                query/default-cumulative-histogram-series-selection)
        document (document/plotje-from-screen screen)
        text (:text document)]
    (is (str/includes? text ":source :histogram-query"))
    (is (str/includes? text ":mode :cumulative-histogram-series"))
    (is (str/includes? text ":metric-kind :histogram"))
    (is (str/includes? text ":temporality :cumulative"))
    (is (str/includes? text ":p95-estimate :p95-lower-bound :p95-upper-bound"))
    (is (str/includes? text ":explicit-bounds"))
    (is (str/includes? text ":interval-count :reset-count :observed-duration-nanos"))
    (is (not (str/includes? text ":sum -83.0")))
    (is (not (str/includes? text "checkout")))
    (is (not (str/includes? text "(10.0, +Inf)")))
    (is (= (:chart screen) (:value document)))))

(deftest metric-distribution-editor-preserves-its-kind-union-query
  (let [screen (sample/screen-for-selection
                {:signal :metrics :field :metric-name
                 :window :15m :limit 10})
        document (document/plotje-from-screen screen)
        text (:text document)]
    (is (str/includes? text ":source :current-query"))
    (is (not (str/includes? text ":source :telemetry-query")))
    (is (not (str/includes? text "queue.depth")))
    (is (= (:chart screen) (:value document)))))

(deftest safe-hiccup-is-data-only-bounded-and-escaped
  (is (= "<section class=\"card\"><h2>Safe</h2><p>&lt;not markup&gt;</p></section>"
         (hiccup/text->html
          "[:section {:class \"card\"} [:h2 \"Safe\"] [:p \"<not markup>\"]]")))
  (doseq [text ["[:script \"alert(1)\"]"
                "[:a {:href \"https://example.invalid\"} \"leave\"]"
                "[:div {:onclick \"alert(1)\"} \"active\"]"
                "#foo/bar {}"]]
    (is (:oscope.hiccup/error (thrown-data #(hiccup/parse-spec text))) text))
  (is (:oscope.hiccup/error
       (thrown-data #(hiccup/parse-spec
                      (str "[:p \"" (apply str (repeat 1001 "x")) "\"]"))))))

(deftest mountable-handler-seeds-current-chart-and-suppresses-every-owned-route
  (let [loads (atom [])
        suppressed (atom 0)
        source {:load-command
                (fn [request-id selection]
                  (swap! loads conj [request-id selection])
                  (sample/screen-for-selection selection))}
        handler (editor/handler
                 source {:path "/admin/telemetry/edit"
                         :viewer-path "/admin/telemetry"
                         :run-suppressed
                         (fn [thunk] (swap! suppressed inc) (thunk))})
        page (handler {:request-method :get
                       :uri "/admin/telemetry/edit/plotje"
                       :query-string
                       "signal=logs&field=severity-text&window=15m&limit=3"})
        asset (handler {:request-method :get
                        :uri "/admin/telemetry/edit/editor.js"})]
    (is (= 200 (:status page)))
    (is (str/includes? (:body page) "Severity Text in Logs"))
    (is (str/includes? (:body page)
                       "data-preview-path=\"/admin/telemetry/edit/plotje/preview?signal=logs&amp;field=severity-text&amp;window=15m&amp;limit=3\""))
    (is (str/includes? (:body page) "href=\"/admin/telemetry\""))
    (is (str/includes? (:body page) "Chart grammar reference &amp; examples"))
    (is (str/includes? (:body page) "<code>:area</code>"))
    (is (str/includes? (:body page) "data-oscope-load-example"))
    (is (str/includes? (:body page) ":point-radius"))
    (is (= {:signal :logs :field :severity-text :window :15m :limit 3}
           (second (first @loads))))
    (is (= 200 (:status asset)))
    (is (str/includes? (:body asset) "f.dataset.previewPath"))
    (is (str/includes? (:body asset) "data-oscope-load-example"))
    (is (not (str/includes? (:body asset) "eval(")))
    (is (= 2 @suppressed))
    (is (nil? (handler {:request-method :get :uri "/not-owned"})))
    (is (= 2 @suppressed))))

(deftest post-fallback-and-progressive-previews-share-one-document-contract
  (let [loads (atom 0)
        handler (editor/handler
                 {:screen sample/default-screen
                  :load-command (fn [& _] (swap! loads inc) sample/default-screen)})
        plotje-text
        "{:title \"Worker queue\" :data [{:service \"worker\" :count 7}] :layers [{:mark :bar :x :service :y :count}]}"
        plotje-page (handler {:request-method :post
                              :uri "/oscope/edit/plotje"
                              :body (form plotje-text)})
        plotje-preview (handler {:request-method :post
                                 :uri "/oscope/edit/plotje/preview"
                                 :body (form plotje-text)})
        invalid-preview (handler {:request-method :post
                                  :uri "/oscope/edit/plotje/preview"
                                  :body (form "{:layers :wrong}")})
        hiccup-page (handler {:request-method :post
                              :uri "/oscope/edit/hiccup"
                              :body (form "[:section [:h2 \"Queue health\"]]")})
        unsafe-preview (handler {:request-method :post
                                 :uri "/oscope/edit/hiccup/preview"
                                 :body (form "[:script \"alert(1)\"]")})]
    (is (= 200 (:status plotje-page)))
    (is (str/includes? (:body plotje-page) "Worker queue"))
    (is (str/includes? (:body plotje-preview) "<svg"))
    (is (str/includes? (:body invalid-preview) "Spec error"))
    (is (str/includes? (:body hiccup-page) "Queue health"))
    (is (str/includes? (:body unsafe-preview) "Spec error"))
    (is (zero? @loads)
        "literal Plotje and Hiccup posts stay detached from telemetry")))

(deftest plotje-preview-rebinds-one-reusable-spec-to-telemetry-expression-results
  (let [loads (atom 0)
        source {:load-command
                (fn [_ selection]
                  (let [n (swap! loads inc)
                        base (sample/screen-for-selection selection)
                        rows [{:value (str "service-" n) :count (+ 10 n)}]]
                    (-> base
                        (assoc-in [:table :rows] rows)
                        (assoc-in [:chart :data] rows))))}
        handler (editor/handler source)
        query "signal=spans&field=service-name&window=15m&limit=10"
        page (handler {:request-method :get :uri "/oscope/edit/plotje"
                       :query-string query})
        text (:text (document/plotje-from-screen
                     (sample/screen-for-selection
                      {:signal :spans :field :service-name
                       :window :15m :limit 10})))
        preview (handler {:request-method :post
                          :uri "/oscope/edit/plotje/preview"
                          :query-string query
                          :body (form text)})]
    (is (str/includes? (:body page) "service-1"))
    (is (str/includes? (:body preview) "service-2"))
    (is (not (str/includes? text "service-1")))
    (is (not (str/includes? text "service-2")))
    (is (= 2 @loads))))

(deftest plotje-preview-reports-missing-telemetry-query-binding
  (let [handler (editor/handler {:screen
                                 (assoc sample/default-screen
                                        :chart nil
                                        :status :empty)})
        text (:text (document/plotje-from-screen sample/default-screen))
        preview (handler {:request-method :post
                          :uri "/oscope/edit/plotje/preview"
                          :body (form text)})]
    (is (str/includes? (:body preview) "Spec error"))
    (is (str/includes? (:body preview)
                       "data source :telemetry-query is not available"))))

(deftest legacy-current-query-references-remain-renderable
  (let [text "{:data {:source :current-query :select [:value :count]} :layers [{:mark :bar :x :value :y :count}]}"
        handler (editor/handler {:screen sample/default-screen})
        preview (handler {:request-method :post
                          :uri "/oscope/edit/plotje/preview"
                          :body (form text)})]
    (is (= 200 (:status preview)))
    (is (str/includes? (:body preview) "gateway"))))

(deftest plotje-editor-round-trips-a-custom-aggregate-expression
  (let [seen (atom nil)
        query {:signal :spans :window :15m :bucket :none
               :group-by [:service-name]
               :filters [{:field :span-name :op :eq :value "request"}]
               :series [{:as :requests :op :count}
                        {:as :errors :op :count
                         :filters [{:field :status-code :op :eq
                                    :value "ERROR"}]}]
               :calculations [{:as :error-rate :op :divide
                               :args [:errors :requests]}]
               :limit 10}
        text (pr-str {:title "Request error rate"
                      :data {:source :telemetry-query :query query
                             :select [:service-name :requests :errors
                                      :error-rate]}
                      :layers [{:mark :bar :x :service-name :y :error-rate}]})
        handler (editor/handler
                 {:screen sample/default-screen
                  :plotje-query-command
                  (fn [_ expression]
                    (reset! seen expression)
                    [{:service-name "checkout" :requests 25 :errors 2
                      :error-rate 0.08}])})
        page (handler {:request-method :post :uri "/oscope/edit/plotje"
                       :body (form text)})]
    (is (= query @seen))
    (is (str/includes? (:body page) ":as :errors"))
    (is (str/includes? (:body page) ":field :status-code"))
    (is (str/includes? (:body page) ":op :divide"))
    (is (str/includes? (:body page) ":error-rate"))
    (is (not (str/includes? text "checkout")))
    (is (not (str/includes? text "0.08")))
    (is (str/includes? (:body page) "checkout"))
    (is (str/includes? (:body page) "Request error rate"))))

(deftest plotje-editor-runs-a-reusable-bucketed-series-without-copying-points
  (let [seen (atom nil)
        query {:signal :spans :window :1h :bucket :5m
               :group-by [:service-name]
               :filters [{:field :span-name :op :eq :value "request"}]
               :series [{:as :requests :op :count}
                        {:as :errors :op :count
                         :filters [{:field :status-code :op :eq
                                    :value "ERROR"}]}]
               :calculations [{:as :error-ratio :op :divide
                               :args [:errors :requests]}]
               :limit 20}
        rows [{:bucket-start-unix-nano 1999999800000000000
               :service-name "checkout" :requests 20 :errors 1
               :error-ratio 0.05}
              {:bucket-start-unix-nano 2000000100000000000
               :service-name "checkout" :requests 25 :errors 2
               :error-ratio 0.08}]
        text (pr-str
              {:title "Request errors over time"
               :data {:source :telemetry-query :query query
                      :select [:bucket-start-unix-nano :service-name
                               :requests :errors :error-ratio]}
               :layers [{:mark :line :x :bucket-start-unix-nano
                         :y :error-ratio :color :service-name}]})
        handler (editor/handler
                 {:screen sample/default-screen
                  :plotje-query-command
                  (fn [_ expression]
                    (reset! seen expression)
                    rows)})
        preview (handler {:request-method :post
                          :uri "/oscope/edit/plotje/preview"
                          :body (form text)})]
    (is (= query @seen))
    (is (= 200 (:status preview)))
    (is (str/includes? (:body preview) "<polyline"))
    (is (str/includes? (:body preview) "Request errors over time"))
    (is (str/includes? text ":bucket :5m"))
    (is (str/includes? text ":bucket-start-unix-nano"))
    (is (not (str/includes? text "1999999800000000000")))
    (is (not (str/includes? text "0.05")))))

(deftest editor-request-bodies-are-bounded-before-decode
  (let [handler (editor/handler {:screen sample/default-screen})
        oversized (byte-array (inc editor/max-body-bytes))
        response (handler {:request-method :post
                           :uri "/oscope/edit/plotje/preview"
                           :body oversized})
        chunks (atom [(byte-array editor/max-body-bytes) (byte-array 1)])
        streaming-body
        (reify http-body/RequestBody
          (body-recv [_]
            (let [chunk (first @chunks)]
              (swap! chunks #(vec (rest %)))
              chunk))
          (body-bytes [_] (throw (ex-info "must consume bounded chunks" {})))
          (body-string [_ _] (throw (ex-info "must consume bounded chunks" {}))))
        streaming-response
        (handler {:request-method :post
                  :uri "/oscope/edit/hiccup/preview"
                  :body streaming-body})]
    (doseq [actual [response streaming-response]]
      (is (= 413 (:status actual)))
      (is (= "close" (get-in actual [:headers "Connection"]))))))

(deftest paths-and-methods-fail-closed
  (is (editor/handled-path? "/oscope/edit" "/oscope/edit/plotje"))
  (is (not (editor/handled-path? "/oscope/edit" "/oscope/edit/unknown")))
  (is (= "/plotje" (editor/plotje-path "/")))
  (is (= "/editor.js" (editor/asset-path "/")))
  (is (:oscope.ui/error
       (thrown-data #(editor/handler {} {:path "relative"}))))
  (let [handler (editor/handler {:screen sample/default-screen})]
    (is (= 405 (:status (handler {:request-method :put
                                  :uri "/oscope/edit/plotje"}))))
    (is (= "GET, POST"
           (get-in (handler {:request-method :put
                             :uri "/oscope/edit/plotje"})
                   [:headers "Allow"])))))
