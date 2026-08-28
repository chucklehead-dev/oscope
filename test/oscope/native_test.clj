(ns oscope.native-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.core :as glitter]
            [glitter.test-renderer :as renderer]
            [oscope.query :as query]
            [oscope.sample :as sample]
            [oscope.ui.glimmer-native :as glimmer]
            [oscope.ui.native :as native]
            [oscope.view-model :as view-model]))

(deftest glitter-renders-headlessly
  (let [r (renderer/renderer)
        root (atom {:tag-name "window" :children []})
        instance (native/create-instance
                  {:screen sample/default-screen
                   :loader sample/screen-for-selection})]
    (try
      (glitter/reconcile r root ((:view instance) @(:model instance)))
      (is (some #(= [:create-element "button"] %) (renderer/events r)))
      (is (some #(= [:create-element "picture"] %) (renderer/events r)))
      (is (some #(= [:set-event-handler "button" :click] %)
                (renderer/events r)))
      (let [store (:chart-store instance)]
        (is (= 1 (count @(:paths store))))
        (let [first-path (first @(:paths store))]
          (is (java.nio.file.Files/exists first-path
                                          (make-array java.nio.file.LinkOption 0)))
          (dotimes [_ 4]
            ((:view instance) @(:model instance)))
          (is (= 2 (count @(:paths store)))
              "a long-lived native collector retains two SVG generations")
          (is (not (java.nio.file.Files/exists
                    first-path (make-array java.nio.file.LinkOption 0)))
              "an SVG older than the active and replacement generations is removed")))
      (finally ((:close! instance))))
    (let [{:keys [directory paths]} (:chart-store instance)]
      (is (every? #(not (java.nio.file.Files/exists
                         % (make-array java.nio.file.LinkOption 0))) @paths))
      (is (not (java.nio.file.Files/exists
                directory (make-array java.nio.file.LinkOption 0)))))))

(deftest empty-screen-needs-no-native-chart-file
  (let [screen (view-model/screen
                (query/compile-query query/default-selection 1700000000000000000)
                [])
        instance (native/create-instance {:screen screen
                                          :loader (constantly screen)})]
    (try
      (is (nil? (:chart screen)))
      (is (not (.contains (pr-str ((:view instance) screen)) ":picture")))
      (is (empty? @(get-in instance [:chart-store :paths])))
      (finally ((:close! instance))))))

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
        (is (= selection (:selection @(:model b))))
        ((:close! b))))))
