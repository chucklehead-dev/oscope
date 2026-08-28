(ns oscope.visualization-editor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.http.body :as http-body]
            [oscope.hiccup.spec :as hiccup]
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
    (is (= 1 (:oscope.visualization-document/version plotje)))
    (is (= :ready (:status plotje)))
    (is (= (:chart sample/default-screen) (:value plotje)))
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
    (is (str/includes? (:body page) "data-preview-path=\"/admin/telemetry/edit/plotje/preview\""))
    (is (str/includes? (:body page) "href=\"/admin/telemetry\""))
    (is (= {:signal :logs :field :severity-text :window :15m :limit 3}
           (second (first @loads))))
    (is (= 200 (:status asset)))
    (is (str/includes? (:body asset) "f.dataset.previewPath"))
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
    (is (zero? @loads) "preview and fallback posts never query telemetry")))

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
