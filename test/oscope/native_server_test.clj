(ns oscope.native-server-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.native-server-main :as native-server]
            [oscope.server :as server]
            [oscope.server-main :as server-main]
            [oscope.ui.native :as native]))

(deftest native-server-shares-source-and-owns-ordered-shutdown
  (let [events (atom [])
        source (Object.)
        lifecycle {:host "127.0.0.1" :port 9194 :source source}]
    (with-redefs [server/start! (fn [options]
                                 (swap! events conj [:start options])
                                 lifecycle)
                  native/run! (fn [actual-source options]
                                (swap! events conj [:ui actual-source options])
                                :closed-window)
                  server-main/stop-until-closed!
                  (fn [actual-lifecycle]
                    (swap! events conj [:stop actual-lifecycle])
                    {:status :closed})]
      (is (= :closed-window
             (native-server/run! {:port 0 :db-spec "chdb:test"}
                                 {:auto-quit-ms 50})))
      (is (= [[:start {:port 0 :db-spec "chdb:test"}]
              [:ui source {:auto-quit-ms 50}]
              [:stop lifecycle]]
             @events)))))

(deftest native-server-still-retires-collector-when-ui-fails
  (let [stopped? (atom false)
        lifecycle {:host "127.0.0.1" :port 9195 :source ::source}]
    (with-redefs [server/start! (constantly lifecycle)
                  native/run! (fn [& _] (throw (ex-info "ui failed" {})))
                  server-main/stop-until-closed!
                  (fn [actual]
                    (is (= lifecycle actual))
                    (reset! stopped? true))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ui failed"
                            (native-server/run! {:port 0})))
      (is @stopped?))))
