(ns oscope.effect
  "Atomic command effect shared by web and native adapters."
  (:require [oscope.command :as command]
            [oscope.raw-export :as raw-export]))

(defn run-command [loader raw-command]
  (let [{:keys [command/type selection]} (command/validate raw-command)
        _ (when-not (= :query type)
            (throw (ex-info "oscope screen effect requires a query command"
                            {:oscope.effect/error true :command/type type})))
        screen (loader selection)]
    (when-not (and (= 1 (:oscope.view/version screen))
                   (= selection (:selection screen))
                   (= selection (get-in screen [:query-plan :selection])))
      (throw (ex-info "oscope loader returned a screen for another query"
                      {:oscope.effect/error true
                       :selection selection :screen screen})))
    screen))

(defn run-export-command [exporter raw-command]
  (let [{:keys [command/type selection]} (command/validate raw-command)]
    (when-not (= :export type)
      (throw (ex-info "oscope export effect requires an export command"
                      {:oscope.effect/error true :command/type type})))
    (let [result (raw-export/validate-result (exporter selection))]
      (when-not (= selection (:selection result))
        (throw (ex-info "oscope exporter returned another selection"
                        {:oscope.effect/error true :selection selection})))
      result)))

(defn apply-command! [state loader raw-command]
  ;; Validation and loading complete before this one whole-screen mutation.
  (reset! state (run-command loader raw-command)))
