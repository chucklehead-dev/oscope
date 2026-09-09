(ns oscope.native-server-main
  "Standalone OTLP/HTTP collector with the shared Glitter oscope viewer."
  (:require [clojure.string :as str]
            [oscope.config-cli :as config-cli]
            [oscope.server :as server]
            [oscope.server-main :as server-main]
            [oscope.ui.native :as native]))

(defn- ui-options []
  (let [raw (System/getenv "OSCOPE_NATIVE_AUTO_QUIT_MS")]
    (if (str/blank? raw)
      {}
      (let [milliseconds (parse-long raw)]
        (when-not (and (integer? milliseconds) (pos? milliseconds))
          (throw (ex-info "OSCOPE_NATIVE_AUTO_QUIT_MS must be a positive integer"
                          {:oscope.native-server/error true
                           :value raw})))
        {:auto-quit-ms milliseconds}))))

(defn run!
  "Own one collector lifecycle and give its exact shared source to Glitter."
  ([server-options] (run! server-options {}))
  ([server-options native-options]
   (let [lifecycle (server/start! server-options)]
     (println (str "oscope native collector receiving OTLP/HTTP at http://"
                   (:host lifecycle) ":" (:port lifecycle)))
     (try
       (native/run! (:source lifecycle) native-options)
       (finally
         (server-main/stop-until-closed! lifecycle))))))

(defn -main [& arguments]
  (let [resolved (config-cli/load-config arguments
                                         (config-cli/system-environment))]
    (if (:check-config? resolved)
      (print (config-cli/check-output resolved))
      (run! (config-cli/server-options resolved) (ui-options)))))
