(ns oscope.ui.selection
  "Portable selection transitions shared by all adapters."
  (:require [oscope.query :as query]))

(defn select-signal [screen signal]
  (let [control (some #(when (= signal (:value %)) %)
                      (get-in screen [:controls :signals]))
        field (or (some #(when (:selected? %) (:value %)) (:fields control))
                  (:value (first (:fields control))))]
    (query/normalize-selection
     (assoc (:selection screen) :signal signal :field field))))
(defn select-field [screen field]
  (query/normalize-selection (assoc (:selection screen) :field field)))
(defn select-window [screen window]
  (query/normalize-selection (assoc (:selection screen) :window window)))
