(ns oscope.live-main
  "Live embedded chDB launcher for snapshot and native adapters."
  (:require [clojure.string :as str]
            [oscope.live :as live]))

(defn- db-spec [argument]
  (let [configured (System/getenv "OSCOPE_CHDB_SPEC")]
    (cond
      (not (str/blank? argument)) argument
      (not (str/blank? configured)) configured
      :else "chdb::memory:")))

(defn -main [& [mode argument]]
  (let [mode (or mode "web") source (live/open! {:db-spec (db-spec argument)})]
    (try
      ;; Keep optional GUI dependencies out of the core dependency graph.
      (case mode
        "web" (do (require 'oscope.ui.web)
                  (println ((resolve 'oscope.ui.web/render-snapshot)
                            (:screen source))))
        "native" (do (require 'oscope.ui.native)
                     ((resolve 'oscope.ui.native/run!) source))
        "glimmer-native" (do (require 'oscope.ui.glimmer-native)
                             ((resolve 'oscope.ui.glimmer-native/run!) source))
        (throw (ex-info "mode must be web, native, or glimmer-native"
                        {:mode mode})))
      (finally (live/close! source)))))
