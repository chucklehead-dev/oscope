(ns oscope.ui.glimmer-native
  "Glimmer adapter for the canonical oscope screen and effect contract."
  (:require [glimmer.core :as ui]
            [glimmer.ratom :as ratom]
            [oscope.command :as command]
            [oscope.effect :as effect]
            [oscope.sample :as sample]
            [oscope.ui.selection :as selection]))

(defn- selected-signal [screen]
  (some #(when (:selected? %) %) (get-in screen [:controls :signals])))
(defn- choices [screen entries transition select!]
  [:hbox {:spacing 6}
   (for [{:keys [value label selected?]} entries]
     [:button {:key value :label (str (when selected? "● ") label)
               :on-click #(select! (transition screen value))}])])
(defn overview [model select!]
  (let [{:keys [title status selection] :as screen} @model
        rows (get-in screen [:table :rows])]
    [:vbox {:spacing 12 :margin 20}
     [:label {:markup [:span {:size "26000" :weight "bold"
                             :foreground "#17324d"} "oscope"]
              :halign :start :xalign 0.0}]
     [:label {:label (str title "  ·  " (name status))
              :halign :start :xalign 0.0}]
     [choices screen (get-in screen [:controls :signals])
      selection/select-signal select!]
     [choices screen (:fields (selected-signal screen))
      selection/select-field select!]
     [choices screen (get-in screen [:controls :windows])
      selection/select-window select!]
     [:separator]
     [:scrolled {:vexpand true}
      [:vbox {:spacing 8}
       (if (seq rows)
         (for [{:keys [value count]} rows]
           [:hbox {:key value :spacing 12}
            [:label {:label value :hexpand true :halign :start :xalign 0.0}]
            [:label {:label (str count) :halign :end :xalign 1.0}]])
         [:label {:label (:empty-message screen) :margin 28}])]]
     [:label {:label (str "bounded result limit " (:limit selection))
              :halign :end :xalign 1.0}]]))

(defn create-instance [{initial-screen :screen source-loader :loader}]
  (let [model (ratom/atom initial-screen)
        closed? (atom false)
        select! (fn [selected]
                  (when @closed?
                    (throw (ex-info "oscope Glimmer adapter is closed"
                                    {:oscope.ui/error true :type ::closed})))
                  (ratom/reset!
                   model
                   (effect/run-command
                    source-loader
                    (command/query-command [:glimmer selected] selected))))
        close! (fn [] (compare-and-set! closed? false true))]
    {:model model :loader source-loader :select! select!
     :view (fn [] (overview model select!))
     :closed? closed? :close! close!}))

(defn run! [source]
  (require 'glimmer-gtk.core)
  (let [{:keys [view] :as instance} (create-instance source)]
    (ui/run view :title "oscope · Glimmer shell" :width 820 :height 600
            :app-id "dev.chucklehead.oscope.glimmer")
    instance))
(defn -main [& _]
  (run! {:screen sample/default-screen :loader sample/screen-for-selection}))
