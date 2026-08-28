(ns oscope.visualization.document
  "Versioned, renderer-neutral Plotje and safe-Hiccup edit documents."
  (:require [oscope.hiccup.spec :as hiccup]
            [oscope.plotje.spec :as plotje]))

(def version 1)
(def kinds #{:plotje :hiccup})
(def default-hiccup-text
  "[:section {:class \"card\"} [:h2 \"Telemetry note\"] [:p \"Edit this safe, data-only Hiccup.\"]]")
(def empty-plotje-spec
  {:title "No telemetry yet"
   :x-label "Value"
   :y-label "Count"
   :width 760
   :height 420
   :data [{:value "No telemetry" :count 0}]
   :layers [{:mark :bar :x :value :y :count}]})

(def ^:private document-keys
  #{:oscope.visualization-document/version :kind :text :status :value :error})

(defn- fail! [type message data]
  (throw (ex-info message
                  (assoc data :oscope.visualization-document/error true
                         :type type))))

(defn- parse [kind text]
  (case kind
    :plotje (plotje/parse-spec text)
    :hiccup (hiccup/parse-spec text)
    (fail! ::unsupported-kind "visualization document kind is unsupported"
           {:kind kind :supported-kinds (vec (sort kinds))})))

(defn max-text-chars [kind]
  (case kind
    :plotje plotje/max-spec-chars
    :hiccup hiccup/max-spec-chars
    (fail! ::unsupported-kind "visualization document kind is unsupported"
           {:kind kind :supported-kinds (vec (sort kinds))})))

(defn prepare
  "Parse bounded editor text into a serializable ready-or-invalid document."
  [kind text]
  (let [text (str (or text ""))
        maximum (max-text-chars kind)]
    ;; Do not retain oversized source text in an otherwise "invalid" document:
    ;; the document itself is a serializable boundary consumed by renderers.
    (when (> (count text) maximum)
      (fail! ::document-too-large
             "visualization document source is too large"
             {:kind kind :actual (count text) :maximum maximum :status 413}))
    (try
      {:oscope.visualization-document/version version
       :kind kind
       :text text
       :status :ready
       :value (parse kind text)
       :error nil}
      (catch clojure.lang.ExceptionInfo error
        {:oscope.visualization-document/version version
         :kind kind
         :text text
         :status :invalid
         :value nil
         :error (subs (str (or (ex-message error) "invalid visualization"))
                      0 (min 400 (count (str (or (ex-message error)
                                                 "invalid visualization")))))}))))

(defn validate-document [document]
  (when-not (map? document)
    (fail! ::invalid-document "visualization document must be a map"
           {:document document}))
  (when-let [unknown (seq (remove document-keys (keys document)))]
    (fail! ::unsupported-document-key
           "visualization document contains unsupported keys"
           {:keys (vec (sort-by str unknown))}))
  (let [expected (prepare (:kind document) (:text document))]
    (when-not (and (= version (:oscope.visualization-document/version document))
                   (= expected document))
      (fail! ::invalid-document
             "visualization document does not match its bounded source text"
             {:document document}))
    document))

(defn plotje-from-screen [screen]
  (prepare :plotje (pr-str (or (:chart screen) empty-plotje-spec))))

(defn default-hiccup []
  (prepare :hiccup default-hiccup-text))
