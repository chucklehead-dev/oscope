(ns oscope.ui.native
  "Glitter/GTK adapter for the canonical oscope screen and effect contract."
  (:require [glitter.app :as app]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]
            [glitter.widget :as widget]
            [jolt.ffi :as ffi]
            [oscope.command :as command]
            [oscope.effect :as effect]
            [oscope.plotje.svg :as plotje-svg]
            [oscope.sample :as sample]
            [oscope.ui.selection :as selection]))

(defn- delete-chart-store! [{:keys [directory paths closed?]}]
  (when (compare-and-set! closed? false true)
    (let [failures (atom [])]
      (doseq [path (reverse @paths)]
        (try
          (java.nio.file.Files/deleteIfExists path)
          (catch Throwable error
            (swap! failures conj [(str path) (ex-message error)]))))
      (try
        (java.nio.file.Files/deleteIfExists directory)
        (catch Throwable error
          (swap! failures conj [(str directory) (ex-message error)])))
      (when (seq @failures)
        (throw (ex-info "oscope native chart files could not be removed"
                        {:oscope.ui/error true :type ::chart-cleanup
                         :failures @failures}))))))

(defn- create-chart-store []
  {:directory (java.nio.file.Files/createTempDirectory
               "oscope-native-chart-"
               (make-array java.nio.file.attribute.FileAttribute 0))
   :paths (atom [])
   :next-id (atom 0)
   :closed? (atom false)})

(def ^:private retained-chart-generations 2)

(defn- render-chart! [{:keys [directory paths next-id closed?]} chart]
  (when chart
    (when @closed?
      (throw (ex-info "oscope native chart store is closed"
                      {:oscope.ui/error true :type ::closed})))
    ;; A distinct path makes Glitter re-apply GtkPicture's :file property on
    ;; every complete-screen replacement. Files remain owned by this adapter
    ;; until window teardown, so GTK never observes a deleted backing file.
    (let [path (.resolve directory (str "chart-" (swap! next-id inc) ".svg"))
          bytes (.getBytes ^String (plotje-svg/spec->svg chart)
                           java.nio.charset.StandardCharsets/UTF_8)]
      (java.nio.file.Files/write path bytes
                                 (make-array java.nio.file.OpenOption 0))
      (let [all-paths (swap! paths conj path)
            stale-paths (drop-last retained-chart-generations all-paths)]
        ;; Retain the file GtkPicture currently owns and the replacement about
        ;; to be reconciled. Anything older is no longer referenced. Keeping
        ;; exactly two generations bounds a long-lived streaming collector
        ;; without deleting the active backing file during reconciliation.
        (doseq [stale stale-paths]
          (java.nio.file.Files/deleteIfExists stale))
        (reset! paths (vec (take-last retained-chart-generations all-paths))))
      (str path))))

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
  ([screen select!] (overview screen select! nil))
  ([{:keys [title status selection] :as screen} select! chart-file]
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
    [:frame {:label title}
     [:vbox {:spacing 10}
      (when chart-file
        [:picture {:file chart-file :content-fit :contain :can-shrink true
                   :alternative-text title :hexpand true :vexpand true}])
      [:scrolled {:vexpand true} (distribution screen)]]]]))

(defn create-instance [{initial-screen :screen source-loader :loader}]
  (let [chart-store (create-chart-store)
        state (atom initial-screen)
        closed? (atom false)
        select! (fn [selected]
                  (when @closed?
                    (throw (ex-info "oscope native adapter is closed"
                                    {:oscope.ui/error true :type ::closed})))
                  (effect/apply-command!
                   state source-loader
                   (command/query-command [:native selected] selected)))
        close! (fn []
                 (when (compare-and-set! closed? false true)
                   (delete-chart-store! chart-store)))]
    {:model state :loader source-loader :select! select!
     :view (fn [screen]
             (overview screen select!
                       (render-chart! chart-store (:chart screen))))
     :closed? closed? :close! close! :chart-store chart-store}))

(defn run!
  ([source] (run! source {}))
  ([source {:keys [auto-quit-ms]}]
   (let [{:keys [model view close!] :as instance} (create-instance source)
         window-closed (promise)
         close-callback (atom nil)
         auto-quit-callback (atom nil)]
     (try
       (app/run (fn [window]
                  (reset! close-callback
                          (ffi/foreign-callable
                           (fn [_window _data]
                             (deliver window-closed :window-closed)
                             ;; FALSE lets GTK continue its normal close.
                             0)
                           [:pointer :pointer] :int :collect-safe))
                  (widget/retain-callable! @close-callback)
                  (g/g-signal-connect-data window "close-request"
                                           @close-callback ffi/null ffi/null
                                           g/CONNECT-DEFAULT)
                  (when auto-quit-ms
                    (reset! auto-quit-callback
                            (ffi/foreign-callable
                             (fn [_data]
                               (deliver window-closed :auto-quit)
                               0)
                             [:pointer] :int :collect-safe))
                    (widget/retain-callable! @auto-quit-callback)
                    ;; Glitter registers its application-quit timer after this
                    ;; activation callback returns. Complete slightly later so
                    ;; the GTK loop has already received that owned quit.
                    (g/g-timeout-add (+ auto-quit-ms 100)
                                     @auto-quit-callback ffi/null))
                  (gtk/mount! window view model))
                :title "oscope · embedded telemetry" :width 960 :height 780
                :app-id "dev.chucklehead.oscope"
                :auto-quit-ms auto-quit-ms)
       ;; Glitter deliberately schedules its application loop asynchronously
       ;; when Jolt's primordial main-thread pump is available. Keep the
       ;; adapter and its shared source alive until the actual window closes.
       ;; Its opt-in smoke timer quits the application without necessarily
       ;; emitting close-request; the adjacent GTK timer covers that path.
       @window-closed
       instance
       (finally
         (when-let [callback @close-callback]
           (widget/release-callable! callback))
         (when-let [callback @auto-quit-callback]
           (widget/release-callable! callback))
         (close!))))))
(defn -main [& _]
  (run! {:screen sample/default-screen :loader sample/screen-for-selection}))
