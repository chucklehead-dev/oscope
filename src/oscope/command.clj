(ns oscope.command
  "Versioned data-only intents shared by all oscope shells."
  (:require [oscope.query :as query]
            [oscope.raw-export :as raw-export]))

(defn query-command [request-id selection]
  {:oscope.command/version 1 :command/type :query :request-id request-id
   :selection (query/normalize-selection selection)})

(defn export-command [request-id selection]
  {:oscope.command/version 1 :command/type :export :request-id request-id
   :selection (raw-export/normalize-selection selection)})

(defn validate [command]
  (when (map? command)
    (when-let [unknown (seq (remove #{:oscope.command/version :command/type
                                     :request-id :selection}
                                   (keys command)))]
      (throw (ex-info "unsupported oscope command keys"
                      {:oscope.command/error true
                       :keys (vec (sort-by str unknown))}))))
  (when-not (and (map? command) (= 1 (:oscope.command/version command))
                 (#{:query :export} (:command/type command))
                 (some? (:request-id command)))
    (throw (ex-info "unsupported oscope command"
                    {:oscope.command/error true :command command})))
  (assoc command :selection
         (case (:command/type command)
           :query (query/normalize-selection (:selection command))
           :export (raw-export/normalize-selection (:selection command)))))
