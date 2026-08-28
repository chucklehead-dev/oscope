(ns oscope.effect
  "Atomic command effect shared by web and native adapters."
  (:require [oscope.command :as command]))

(defn run-command [loader raw-command]
  (let [{:keys [selection]} (command/validate raw-command)
        screen (loader selection)]
    (when-not (and (= 1 (:oscope.view/version screen))
                   (= selection (:selection screen))
                   (= selection (get-in screen [:query-plan :selection])))
      (throw (ex-info "oscope loader returned a screen for another query"
                      {:oscope.effect/error true
                       :selection selection :screen screen})))
    screen))

(defn apply-command! [state loader raw-command]
  ;; Validation and loading complete before this one whole-screen mutation.
  (reset! state (run-command loader raw-command)))
