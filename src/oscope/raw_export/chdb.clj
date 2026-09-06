(ns oscope.raw-export.chdb
  "Embedded chDB execution adapter for pure oscope raw-export contracts."
  (:require [db.jdbc]
            [jdbc.chdb :as chdb]
            [oscope.raw-export :as raw-export]))

(defn execute! [connection selection]
  (let [{:keys [selection query]} (raw-export/compile-query selection)
        {:keys [format max-rows max-bytes]} selection
        result (chdb/query-bytes connection query
                                 {:format format :max-rows max-rows
                                  :max-bytes max-bytes})]
    (when-not (and (= format (:format result))
                   (integer? (:byte-count result))
                   (<= 0 (:byte-count result) max-bytes)
                   (bytes? (:bytes result))
                   (= (:byte-count result) (count (:bytes result))))
      (throw (ex-info "jolt-chdb returned an invalid encoded export"
                      {:oscope.export/error true
                       :type ::invalid-driver-result
                       :format (:format result)
                       :byte-count (:byte-count result)})))
    (raw-export/validate-result
     (merge {:oscope.export/version 1 :selection selection
             :byte-count (:byte-count result) :bytes (:bytes result)}
            (raw-export/response-metadata selection)))))
