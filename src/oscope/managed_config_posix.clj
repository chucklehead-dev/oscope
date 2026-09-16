(ns oscope.managed-config-posix
  "Linux POSIX persistence edge for the managed configuration store.

  Paths, errno values, descriptors, and native exceptions never cross this
  namespace's operations map. The only error data consumed by the store is a
  closed `:managed-reason`."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]))

(ffi/load-library)

(ffi/defcfn c-open "open" [:string :int :&] :int
  {:capture-native-error true})
(ffi/defcfn c-openat "openat" [:int :string :int :&] :int
  {:capture-native-error true})
(ffi/defcfn c-flock "flock" [:int :int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn c-fsync "fsync" [:int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn c-mkdir "mkdir" [:string :int] :int
  {:capture-native-error true})
(ffi/defcfn c-lseek "lseek" [:int :int64 :int] :int64
  {:capture-native-error true})
(ffi/defcfn c-read "read" [:int :pointer :size_t] :ssize_t
  {:blocking true :capture-native-error true})
(ffi/defcfn c-write "write" [:int :pointer :size_t] :ssize_t
  {:blocking true :capture-native-error true})
(ffi/defcfn c-fstat "fstat" [:int :pointer] :int
  {:capture-native-error true})
(ffi/defcfn c-geteuid "geteuid" [] :uint)
(ffi/defcfn c-renameat "renameat" [:int :string :int :string] :int
  {:capture-native-error true})
(ffi/defcfn c-unlinkat "unlinkat" [:int :string :int] :int
  {:capture-native-error true})
(ffi/defcfn c-close "close" [:int] :int
  {:capture-native-error true})

(def ^:private o-rdonly 0)
(def ^:private o-rdwr 2)
(def ^:private o-creat 64)
(def ^:private o-excl 128)
(def ^:private o-directory 65536)
(def ^:private o-nofollow 131072)
(def ^:private mode-0600 384)
(def ^:private mode-0700 448)
(def ^:private mode-mask 511)
(def ^:private type-mask 61440)
(def ^:private type-regular 32768)
(def ^:private type-directory 16384)
(def ^:private lock-exclusive 2)
(def ^:private lock-nonblocking 4)
(def ^:private eintr 4)
(def ^:private enoent 2)
(def ^:private eexist 17)
(def ^:private seek-set 0)
(def ^:private stat-bytes 256)
(def ^:private stat-mode-offset 24)
(def ^:private stat-uid-offset 28)
(def ^:private stat-size-offset 48)
(def ^:private buffer-bytes 65536)

(defn- supported-host? []
  (let [os (str/lower-case (or (System/getProperty "os.name") ""))
        arch (str/lower-case (or (System/getProperty "os.arch") ""))]
    (and (str/includes? os "linux")
         (contains? #{"amd64" "x86_64"} arch))))

(defn- failure [reason]
  (ex-info "managed configuration POSIX operation failed"
           {:managed-reason reason}))

(defn- retry-zero! [call reason]
  (loop []
    (let [[result error] (call)]
      (cond
        (zero? result) nil
        (= eintr error) (recur)
        :else (throw (failure reason))))))

(defn- open-result [path flags create?]
  (loop []
    (let [[fd error] (if create?
                       (c-open (str path) flags mode-0600)
                       (c-open (str path) flags))]
      (if (and (neg? fd) (= eintr error))
        (recur)
        [fd error]))))

(defn- open! [path flags create? reason]
  (let [[fd _] (open-result path flags create?)]
    (when (neg? fd) (throw (failure reason)))
    fd))

(defn- openat-result [directory-fd name flags create?]
  (loop []
    (let [[fd error] (if create?
                       (c-openat directory-fd name flags mode-0600)
                       (c-openat directory-fd name flags))]
      (if (and (neg? fd) (= eintr error))
        (recur)
        [fd error]))))

(defn- openat! [directory-fd name flags create? reason]
  (let [[fd _] (openat-result directory-fd name flags create?)]
    (when (neg? fd) (throw (failure reason)))
    fd))

(defn- close! [fd]
  (let [[result _] (c-close fd)]
    (when (neg? result) (throw (failure :store-failed)))))

(defn- with-fd [fd f]
  (let [outcome (try {:value (f fd)}
                     (catch Throwable error {:error error}))]
    (try
      (close! fd)
      (catch Throwable close-error
        (when-not (:error outcome)
          (throw close-error))))
    (if-let [error (:error outcome)]
      (throw error)
      (:value outcome))))

(defn- stat! [fd expected-type expected-mode reason]
  (ffi/with-alloc [buffer stat-bytes]
    (retry-zero! #(c-fstat fd buffer) reason)
    (let [mode (ffi/read buffer :uint32 stat-mode-offset)
          uid (ffi/read buffer :uint32 stat-uid-offset)
          size (ffi/read buffer :int64 stat-size-offset)]
      (when-not (and (= expected-type (bit-and mode type-mask))
                     (= expected-mode (bit-and mode mode-mask))
                     (= uid (c-geteuid)))
        (throw (failure :permission-unverified)))
      size)))

(defn- verified-directory-fd [path]
  (let [fd (open! path (bit-or o-rdonly o-directory o-nofollow) false
                  :unsafe-directory)]
    (try
      (stat! fd type-directory mode-0700 :unsafe-directory)
      fd
      (catch Throwable error
        (try (close! fd) (catch Throwable _ nil))
        (throw error)))))

(defn- verified-file-fd [path flags reason]
  (let [fd (open! path (bit-or flags o-nofollow) false reason)]
    (try
      (stat! fd type-regular mode-0600 reason)
      fd
      (catch Throwable error
        (try (close! fd) (catch Throwable _ nil))
        (throw error)))))

(defn- read-fd! [fd max-bytes reason]
  (let [size (stat! fd type-regular mode-0600 reason)]
    (when (or (neg? size) (> size max-bytes))
      (throw (failure reason)))
    (loop []
      (let [[position error] (c-lseek fd 0 seek-set)]
        (cond
          (zero? position) nil
          (= eintr error) (recur)
          :else (throw (failure reason)))))
    (let [result (byte-array size)]
      (ffi/with-alloc [buffer buffer-bytes]
        (loop [offset 0]
          (if (= offset size)
            (let [[n error] (c-read fd buffer 1)]
              (cond
                (zero? n) result
                (= eintr error) (recur offset)
                :else (throw (failure reason))))
            (let [limit (min buffer-bytes (- size offset))
                  [n error] (c-read fd buffer limit)]
              (cond
                (pos? n) (do (ffi/read-into! buffer result offset n)
                             (recur (+ offset n)))
                (= eintr error) (recur offset)
                :else (throw (failure reason))))))))))

(defn- read-file-at! [directory-fd name max-bytes]
  (let [[fd error] (openat-result directory-fd name
                                  (bit-or o-rdonly o-nofollow) false)]
    (cond
      (not (neg? fd)) (with-fd fd #(read-fd! % max-bytes :unreadable))
      (= enoent error) nil
      :else (throw (failure :unsafe-target)))))

(defn- read-file! [target max-bytes]
  (let [directory (.getParent target)
        [directory-fd error]
        (open-result directory (bit-or o-rdonly o-directory o-nofollow) false)]
    (cond
      (not (neg? directory-fd))
      (with-fd directory-fd
        (fn [fd]
          (stat! fd type-directory mode-0700 :unsafe-directory)
          (read-file-at! fd (str (.getFileName target)) max-bytes)))
      (= enoent error) nil
      :else (throw (failure :unsafe-directory)))))

(defn- ensure-directory! [target]
  (let [directory (.getParent target)
        parent (.getParent directory)]
    (when (nil? parent) (throw (failure :unsafe-directory)))
    ;; The parent is not Oscope-owned, but must be an actual directory rather
    ;; than a link. The child directory is always proved private and owned.
    (with-fd (open! parent (bit-or o-rdonly o-directory o-nofollow) false
                    :unsafe-directory)
      (fn [fd]
        (ffi/with-alloc [buffer stat-bytes]
          (retry-zero! #(c-fstat fd buffer) :unsafe-directory)
          (when-not (= type-directory
                       (bit-and (ffi/read buffer :uint32 stat-mode-offset)
                                type-mask))
            (throw (failure :unsafe-directory))))))
    (loop []
      (let [[result error] (c-mkdir (str directory) mode-0700)]
        (cond
          (zero? result) nil
          (= eexist error) nil
          (= eintr error) (recur)
          :else (throw (failure :unsafe-directory)))))
    (with-fd (verified-directory-fd directory) (constantly nil))))

(defn- write-all! [fd bytes]
  (let [length (alength bytes)]
    (ffi/with-alloc [buffer length]
      (ffi/write-array buffer bytes)
      (loop [offset 0]
        (when (< offset length)
          (let [[n error] (c-write fd (+ buffer offset) (- length offset))]
            (cond
              (pos? n) (recur (+ offset n))
              (= eintr error) (recur offset)
              :else (throw (failure :write-failed)))))))))

(defn- close-token! [token]
  (when-let [fd @(:fd token)]
    (reset! (:fd token) nil)
    (close! fd)))

(defn- unlink-temp! [token]
  (loop []
    (let [[result error]
          (c-unlinkat (:directory-fd token) (:name token) 0)]
      (cond
        (zero? result) nil
        (= enoent error) nil
        (= eintr error) (recur)
        :else (throw (failure :temporary-file-failed))))))

(defn operations
  "Return the Linux x86-64 POSIX operations map used by the pure store.

  Other hosts are intentionally unsupported until their native ABI and crash
  semantics are exercised independently."
  ([max-bytes] (operations max-bytes {}))
  ([max-bytes {:keys [nonblocking-lock?]}]
  (if-not (supported-host?)
    {:supported? false}
    {:supported? true
     :read-bytes (fn [target lock]
                   (if lock
                     (read-file-at! (:directory-fd lock)
                                    (str (.getFileName target)) max-bytes)
                     (read-file! target max-bytes)))
     :ensure-directory! ensure-directory!
     :acquire-lock!
     (fn [target]
       (let [directory-fd (verified-directory-fd (.getParent target))
             fd (try
                  (openat! directory-fd ".oscope-config.lock"
                           (bit-or o-rdwr o-creat o-nofollow) true
                           :unsafe-target)
                  (catch Throwable error
                    (try (close! directory-fd) (catch Throwable _ nil))
                    (throw error)))]
         (try
           (stat! fd type-regular mode-0600 :permission-unverified)
           (retry-zero! #(c-flock fd (if nonblocking-lock?
                                     (bit-or lock-exclusive lock-nonblocking)
                                     lock-exclusive)) :conflict)
           {:lock-fd fd :directory-fd directory-fd}
           (catch Throwable error
             (try (close! fd) (catch Throwable _ nil))
             (try (close! directory-fd) (catch Throwable _ nil))
             (throw error)))))
     :release-lock! (fn [lock]
                      (let [lock-error (try (close! (:lock-fd lock)) nil
                                            (catch Throwable error error))]
                        (close! (:directory-fd lock))
                        (when lock-error (throw lock-error))))
     :create-temp!
     (fn [_ lock]
       (loop []
         (let [name (str ".config.edn-" (random-uuid) ".tmp")
               [fd error] (openat-result
                           (:directory-fd lock) name
                           (bit-or o-rdwr o-creat o-excl o-nofollow) true)]
           (cond
             (not (neg? fd))
             (let [token {:name name :directory-fd (:directory-fd lock)
                          :fd (atom fd)}]
               (try
                 (stat! fd type-regular mode-0600 :permission-unverified)
                 token
                 (catch Throwable failure
                   (try (close-token! token) (catch Throwable _ nil))
                   (try (unlink-temp! token) (catch Throwable _ nil))
                   (throw failure))))
             (= eexist error) (recur)
             :else (throw (failure :temporary-file-failed))))))
     :write! (fn [token bytes]
               (if-let [fd @(:fd token)]
                 (write-all! fd bytes)
                 (throw (failure :write-failed))))
     :force-file! (fn [token]
                    (if-let [fd @(:fd token)]
                      (retry-zero! #(c-fsync fd) :force-failed)
                      (throw (failure :force-failed))))
     :verify-temp! (fn [token]
                     (if-let [fd @(:fd token)]
                       (stat! fd type-regular mode-0600 :permission-unverified)
                       (throw (failure :permission-unverified))))
     :read-temp! (fn [token]
                   (if-let [fd @(:fd token)]
                     (read-fd! fd max-bytes :readback-failed)
                     (throw (failure :readback-failed))))
     :atomic-replace!
     (fn [token target lock]
       ;; If a target exists, prove it before native rename replaces it. Never
       ;; pre-delete: rename supplies same-directory atomic replacement.
       (when-let [fd (let [[fd error]
                           (openat-result (:directory-fd lock)
                                          (str (.getFileName target))
                                          (bit-or o-rdonly o-nofollow) false)]
                       (cond (not (neg? fd)) fd
                             (= enoent error) nil
                             :else (throw (failure :unsafe-target))))]
         (with-fd fd #(stat! % type-regular mode-0600 :permission-unverified)))
       (close-token! token)
       (retry-zero! #(c-renameat (:directory-fd lock) (:name token)
                                 (:directory-fd lock)
                                 (str (.getFileName target)))
                    :atomic-replace-failed))
     :verify-target! (fn [target lock]
                       (with-fd (openat! (:directory-fd lock)
                                         (str (.getFileName target))
                                         (bit-or o-rdonly o-nofollow) false
                                         :permission-unverified)
                         #(stat! % type-regular mode-0600
                                 :permission-unverified))
                       nil)
     :force-directory! (fn [_ lock]
                         (stat! (:directory-fd lock) type-directory mode-0700
                                :unsafe-directory)
                         (retry-zero! (fn [] (c-fsync (:directory-fd lock)))
                                      :directory-force-failed))
     :delete-temp! (fn [token]
                     (let [close-error (try (close-token! token) nil
                                            (catch Throwable error error))]
                       (unlink-temp! token)
                       (when close-error (throw close-error))))})))
