(ns oscope.managed-config-worker
  "Subprocess participant for the managed-config flock/CAS proof."
  (:require [oscope.config :as config]
            [oscope.config-cli :as config-cli]
            [oscope.managed-config-posix :as posix]
            [oscope.managed-config-store :as store])
  (:import [java.nio.file Files Paths]
           [java.nio.file.attribute FileAttribute]))

(def no-file-attributes (make-array FileAttribute 0))

(defn- marker! [path value]
  (Files/write (Paths/get path (make-array String 0))
               (.getBytes value "UTF-8")
               (make-array java.nio.file.OpenOption 0)))

(defn- wait-for! [path]
  (let [target (Paths/get path (make-array String 0))
        deadline (+ (System/nanoTime) 30000000000)]
    (loop []
      (cond
        (Files/exists target (make-array java.nio.file.LinkOption 0)) nil
        (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
        :else (throw (ex-info "managed configuration worker timed out" {}))))))

(defn -main [root expected-revision port ready start result]
  (let [managed (store/managed-store
                 [] {"XDG_CONFIG_HOME" root}
                 {"os.name" "Linux" "os.arch" "amd64"
                  "user.home" "/ignored"}
                 config-cli/config-file?
                 (posix/operations store/max-config-bytes))]
    (marker! ready "ready")
    (wait-for! start)
    (let [outcome (store/replace!
                   managed expected-revision
                   (assoc-in config/defaults [:server :port]
                             (parse-long port)))]
      (marker! result (name (or (:reason outcome) (:status outcome)))))))
