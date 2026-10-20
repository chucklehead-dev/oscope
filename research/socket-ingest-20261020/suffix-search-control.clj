(require '[clojure.data.json :as json])
(let [target (ns-resolve 'clojure.data.json 'next-string-special)
      _ (assert target "Requires selected data.json fast-reader fork")
      original @target]
  (doseq [n [16 32 64 128]]
    (let [input (str "[" (clojure.string/join "," (repeat n "\"\"")) "]")
          calls (atom 0) visits (atom 0)
          actual (with-redefs-fn
                   {target (fn [text start]
                             (swap! calls inc)
                             ;; Absent backslash: source-derived miss distance,
                             ;; not a native instruction counter or timing.
                             (swap! visits + (- (count text) start))
                             (original text start))}
                   #(json/read-str input))]
      (assert (= (vec (repeat n "")) actual))
      (assert (= n @calls))
      (prn {:strings n :input-characters (count input) :actual-helper-calls @calls
            :modeled-escape-search-visits @visits}))))
