(ns oscope.hiccup.spec
  "Bounded, data-only Hiccup accepted by oscope visualization surfaces."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def max-spec-chars 16384)
(def ^:private max-nodes 256)
(def ^:private max-depth 12)
(def ^:private max-text-chars 12000)
(def ^:private max-text-node-chars 1000)
(def ^:private max-attribute-chars 160)

(def ^:private tags
  #{:div :section :article :header :footer :main :h1 :h2 :h3 :p :pre :code
    :ul :ol :li :dl :dt :dd :table :thead :tbody :tr :th :td :span :strong
    :em :small :br})
(def ^:private attributes
  #{:id :class :title :aria-label :role :colspan :rowspan})
(def ^:private reserved-ids
  #{"hiccup-preview" "plotje-preview" "oscope-screen" "oscope-editor"})

(defn- fail! [message]
  (throw (ex-info message {:oscope.hiccup/error true})))

(defn- esc [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn validate-spec [root]
  (let [nodes (atom 0)
        text-size (atom 0)]
    (letfn [(walk [value depth]
              (when (> depth max-depth)
                (fail! "Hiccup exceeds depth 12"))
              (when (> (swap! nodes inc) max-nodes)
                (fail! "Hiccup exceeds 256 nodes"))
              (cond
                (string? value)
                (do
                  (when (> (count value) max-text-node-chars)
                    (fail! "text node exceeds 1000 characters"))
                  (when (> (swap! text-size + (count value)) max-text-chars)
                    (fail! "total text is too large"))
                  value)

                (number? value) value

                (vector? value)
                (let [tag (first value)
                      with-attributes? (map? (second value))
                      attribute-map (if with-attributes? (second value) {})
                      children (if with-attributes? (drop 2 value) (rest value))]
                  (when-not (contains? tags tag)
                    (fail! (str "tag " (pr-str tag) " is not allowed")))
                  (doseq [[key attribute-value] attribute-map]
                    (when-not (contains? attributes key)
                      (fail! (str "attribute " (pr-str key) " is not allowed")))
                    (when-not (or (string? attribute-value)
                                  (number? attribute-value))
                      (fail! "attribute values must be strings or numbers"))
                    (when (> (count (str attribute-value)) max-attribute-chars)
                      (fail! "attribute value is too long"))
                    (when (and (= :id key)
                               (or (contains? reserved-ids (str attribute-value))
                                   (not (re-matches
                                         #"[A-Za-z][A-Za-z0-9_-]{0,63}"
                                         (str attribute-value)))))
                      (fail! "id must be a short non-reserved HTML identifier")))
                  (into [tag attribute-map]
                        (map #(walk % (inc depth)) children)))

                :else
                (fail! "Hiccup nodes must be vectors, strings, or numbers")))]
      (walk root 0))))

(defn parse-spec [text]
  (let [text (str (or text ""))]
    (when (> (count text) max-spec-chars)
      (fail! "Hiccup input is too large"))
    (try
      (validate-spec
       (edn/read-string
        {:readers {}
         :default (fn [_ _] (fail! "tagged literals are not allowed"))}
        text))
      (catch clojure.lang.ExceptionInfo error (throw error))
      (catch Throwable _ (fail! "Hiccup is not valid EDN")))))

(declare spec->html)

(defn spec->html [node]
  (let [node (validate-spec node)]
    (cond
      (string? node) (esc node)
      (number? node) (str node)
      (vector? node)
      (let [[tag attribute-map & children] node
            encoded-attributes
            (apply str
                   (for [[key value] (sort-by (comp name key) attribute-map)]
                     (str " " (name key) "=\"" (esc value) "\"")))]
        (if (= :br tag)
          (str "<br" encoded-attributes ">")
          (str "<" (name tag) encoded-attributes ">"
               (apply str (map spec->html children))
               "</" (name tag) ">"))))))

(defn text->html [text]
  (spec->html (parse-spec text)))
