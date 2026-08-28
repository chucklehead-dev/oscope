(ns oscope.native-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.core :as glitter]
            [glitter.test-renderer :as renderer]
            [oscope.sample :as sample]
            [oscope.ui.glimmer-native :as glimmer]
            [oscope.ui.native :as native]))

(deftest glitter-renders-headlessly
  (let [r (renderer/renderer) root (atom {:tag-name "window" :children []})]
    (glitter/reconcile r root (native/overview sample/default-screen))
    (is (some #(= [:create-element "button"] %) (renderer/events r)))
    (is (some #(= [:set-event-handler "button" :click] %)
              (renderer/events r)))))

(deftest adapters-do-not-share-state-or-live-callbacks
  (doseq [[label create] [[:glitter native/create-instance]
                          [:glimmer glimmer/create-instance]]]
    (testing (name label)
      (let [a (create {:screen sample/default-screen
                       :loader sample/screen-for-selection})
            b (create {:screen sample/default-screen
                       :loader sample/screen-for-selection})
            selection {:signal :logs :field :severity-text :window :15m :limit 7}]
        ((:select! a) selection)
        (is (= selection (:selection @(:model a))))
        (is (= (:selection sample/default-screen)
               (:selection @(:model b))))
        ((:close! a))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"adapter is closed"
                              ((:select! a) selection)))
        ((:select! b) selection)
        (is (= selection (:selection @(:model b))))))))
