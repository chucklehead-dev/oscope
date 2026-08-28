(ns oscope.ui.native
  "Glitter/GTK adapter for the canonical oscope screen and effect contract."
  (:require [glitter.app :as app]
            [glitter.gtk :as gtk]
            [oscope.command :as command]
            [oscope.effect :as effect]
            [oscope.sample :as sample]
            [oscope.ui.selection :as selection]))

(defn- selected-signal [screen]
  (some #(when (:selected? %) %) (get-in screen [:controls :signals])))
(defn- choice [label selected? selected select!]
  [:button {:label (str (when selected? "● ") label)
            :class (when selected? ["suggested-action"])
            :on {:click (fn [_] (select! selected))}}])
(defn- choices [screen entries transition select!]
  [:hbox {:spacing 6}
   (for [{:keys [value label selected?]} entries]
     (choice label selected? (transition screen value) select!))])
(defn- bar [count maximum]
  (apply str (repeat (max 1 (quot (* 28 count) (max 1 maximum))) "█")))
(defn- distribution [screen]
  (let [rows (get-in screen [:table :rows])
        maximum (apply max (cons 0 (map :count rows)))]
    (if (seq rows)
      [:vbox {:spacing 7}
       (for [{:keys [value count]} rows]
         [:hbox {:glitter/key value :spacing 10}
          [:label {:label value :width-chars 24 :ellipsize :end
                   :halign :start :xalign 0.0}]
          [:label {:label (bar count maximum) :hexpand true
                   :halign :start :xalign 0.0 :class ["accent"]}]
          [:label {:label (str count) :width-chars 8
                   :halign :end :xalign 1.0}]])]
      [:label {:label (:empty-message screen) :margin 28
               :halign :center :xalign 0.5}])))

(defn overview
  ([screen] (overview screen (fn [_] nil)))
  ([{:keys [title status selection] :as screen} select!]
   [:vbox {:spacing 12 :margin 20}
    [:hbox {:spacing 12}
     [:vbox {:spacing 2 :hexpand true}
      [:label {:label "oscope" :halign :start :xalign 0.0 :class ["title-1"]}]
      [:label {:label title :halign :start :xalign 0.0}]]
     [:label {:label (str "● " (name status)) :halign :end}]]
    [:separator]
    [:label {:label "Signal" :halign :start :xalign 0.0 :class ["heading"]}]
    (choices screen (get-in screen [:controls :signals]) selection/select-signal select!)
    [:label {:label "Field" :halign :start :xalign 0.0 :class ["heading"]}]
    (choices screen (:fields (selected-signal screen)) selection/select-field select!)
    [:hbox {:spacing 12}
     [:label {:label "Window" :valign :center}]
     (choices screen (get-in screen [:controls :windows]) selection/select-window select!)
     [:label {:label (str "limit " (:limit selection)) :hexpand true :halign :end}]]
    [:frame {:label title} [:scrolled {:vexpand true} (distribution screen)]]]))

(defn create-instance [{initial-screen :screen source-loader :loader}]
  (let [state (atom initial-screen)
        closed? (atom false)
        select! (fn [selected]
                  (when @closed?
                    (throw (ex-info "oscope native adapter is closed"
                                    {:oscope.ui/error true :type ::closed})))
                  (effect/apply-command!
                   state source-loader
                   (command/query-command [:native selected] selected)))
        close! (fn [] (compare-and-set! closed? false true))]
    {:model state :loader source-loader :select! select!
     :view (fn [screen] (overview screen select!))
     :closed? closed? :close! close!}))

(defn run! [source]
  (let [{:keys [model view] :as instance} (create-instance source)]
    (app/run (fn [window] (gtk/mount! window view model))
             :title "oscope · embedded telemetry" :width 860 :height 640
             :app-id "dev.chucklehead.oscope")
    instance))
(defn -main [& _]
  (run! {:screen sample/default-screen :loader sample/screen-for-selection}))
