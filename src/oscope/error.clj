(ns oscope.error
  "Stable formatting helpers for validation errors.")

(defn sorted-keys [keys]
  (vec (sort-by str keys)))
