(ns oscope.command
  "Versioned data-only intents shared by all oscope shells."
  (:require [oscope.query :as query]))

(defn query-command [request-id selection]
  {:oscope.command/version 1 :command/type :query :request-id request-id
   :selection (query/normalize-selection selection)})

(defn validate [command]
  (when-not (and (map? command)
                 (= 1 (:oscope.command/version command))
                 (= :query (:command/type command))
                 (some? (:request-id command)))
    (throw (ex-info "unsupported oscope command"
                    {:oscope.command/error true :command command})))
  (assoc command :selection (query/normalize-selection (:selection command))))
