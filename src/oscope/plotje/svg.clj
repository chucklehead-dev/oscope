(ns oscope.plotje.svg
  "Jolt-portable SVG renderer for oscope's bounded Plotje subset."
  (:require [clojure.string :as str]
            [oscope.plotje.spec :as spec]))

(def ^:private margins {:left 70.0 :right 24.0 :top 46.0 :bottom 58.0})
(defn- esc [x]
  (-> (str (or x "")) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))
(defn- finite-double? [x]
  (let [d (double x)]
    (and (= d d) (not= d ##Inf) (not= d ##-Inf))))
(defn- fmt [x]
  (when-not (finite-double? x)
    (throw (ex-info "plotje produced a non-finite SVG coordinate"
                    {:oscope.plotje/error true
                     :type ::non-finite-coordinate})))
  (format "%.2f" (double x)))
(defn- extent [xs]
  [(double (apply min xs)) (double (apply max xs))])
(defn- domain-position [low high x]
  (let [x (double x)]
    (cond
      (= low high) 0.5
      (= x low) 0.0
      (= x high) 1.0
      :else
      ;; Scale before subtraction so a finite domain spanning roughly
      ;; [-Double/MAX_VALUE, Double/MAX_VALUE] does not overflow its width.
      ;; Distinct adjacent doubles can collapse during normalization; endpoint
      ;; checks above retain their exact placement and other collapsed values
      ;; deterministically use the center.
      (let [scale (max (abs low) (abs high) (abs x))
            scale (if (zero? scale) 1.0 scale)
            scaled-low (/ low scale)
            scaled-high (/ high scale)
            width (- scaled-high scaled-low)
            position (if (zero? width)
                       0.5
                       (/ (- (/ x scale) scaled-low) width))]
        (max 0.0 (min 1.0 position))))))
(defn- linear [[low high] out-low out-high]
  (fn [x] (+ out-low (* (domain-position low high x)
                         (- out-high out-low)))))
(defn- color-map [rows column palette]
  (zipmap (vec (distinct (map column rows))) (cycle palette)))
(defn- line-svg [rows {:keys [x y color stroke opacity stroke-width]} sx sy palette]
  (let [groups (if color (group-by color rows) {nil rows})
        colors (if color (color-map rows color palette) {nil (or stroke (first palette))})]
    (apply str
           (for [[group group-rows] groups
                 :let [sorted-rows (sort-by x group-rows)
                       points (->> sorted-rows
                                   (map #(str (fmt (sx (x %))) ","
                                              (fmt (sy (y %)))))
                                   (str/join " "))]]
             (str "<polyline fill=\"none\" stroke=\"" (or stroke (colors group))
                  "\" stroke-width=\"" (fmt (or stroke-width 2.0))
                  "\" opacity=\"" (fmt (or opacity 1.0))
                  "\" points=\"" points "\"/>"
                  ;; SVG does not paint a one-vertex polyline. Keep the line
                  ;; contract visible by using a point at the same coordinate.
                  (when (= 1 (count sorted-rows))
                    (let [row (first sorted-rows)]
                      (str "<circle class=\"plotje-line-singleton\" cx=\""
                           (fmt (sx (x row))) "\" cy=\"" (fmt (sy (y row)))
                           "\" r=\"3.00\" fill=\"" (or stroke (colors group))
                           "\" fill-opacity=\"" (fmt (or opacity 1.0))
                           "\"/>"))))))))
(defn- point-svg [rows {:keys [x y color fill opacity point-radius]} sx sy palette]
  (let [colors (if color (color-map rows color palette) {})]
    (apply str
           (for [row rows]
             (str "<circle cx=\"" (fmt (sx (x row))) "\" cy=\""
                  (fmt (sy (y row))) "\" r=\"" (fmt (or point-radius 3.0))
                  "\" fill=\"" (or fill (if color (colors (color row)) (first palette)))
                  "\" fill-opacity=\"" (fmt (or opacity 0.75)) "\"/>")))))
(defn- bar-svg [rows {:keys [x y color fill opacity bar-width]} sx sy baseline default-width palette]
  (let [colors (if color (color-map rows color palette) {})
        width (* default-width (/ (double (or bar-width 0.72)) 0.72))]
  (apply str
         (for [row rows
               :let [center (sx (x row)) endpoint (sy (y row))
                     top (min baseline endpoint) height (abs (- baseline endpoint))]]
           (str "<rect x=\"" (fmt (- center (/ width 2.0)))
                "\" y=\"" (fmt top) "\" width=\"" (fmt width)
                "\" height=\"" (fmt height) "\" fill=\""
                (or fill (if color (colors (color row)) (first palette)))
                "\" fill-opacity=\"" (fmt (or opacity 0.75)) "\"/>")))))
(defn- area-svg [rows {:keys [x y fill stroke opacity stroke-width]} sx sy baseline palette]
  (let [sorted-rows (sort-by x rows)
        points (->> sorted-rows
                    (map #(str (fmt (sx (x %))) "," (fmt (sy (y %))))))
        first-row (first sorted-rows)
        first-x (sx (x first-row))
        last-x (sx (x (last sorted-rows)))
        stroke (or stroke (first palette))
        stroke-width (or stroke-width 2.0)
        opacity (or opacity 0.3)]
    (if (= 1 (count sorted-rows))
      ;; A singleton has no horizontal area. Render its explicit degenerate
      ;; form as the baseline-to-value segment instead of relying on a
      ;; zero-area polygon's implementation-defined visibility.
      (str "<line class=\"plotje-area-singleton\" x1=\"" (fmt first-x)
           "\" x2=\"" (fmt first-x) "\" y1=\"" (fmt baseline)
           "\" y2=\"" (fmt (sy (y first-row))) "\" stroke=\"" stroke
           "\" stroke-width=\"" (fmt stroke-width) "\" opacity=\""
           (fmt opacity) "\"/>")
      (str "<polygon points=\"" (fmt first-x) "," (fmt baseline) " "
           (str/join " " points) " " (fmt last-x) "," (fmt baseline)
           "\" fill=\"" (or fill (first palette)) "\" fill-opacity=\""
           (fmt opacity) "\" stroke=\"" stroke
           "\" stroke-width=\"" (fmt stroke-width) "\"/>"))))
(defn- rule-svg [rows {:keys [y stroke opacity stroke-width]} sy left right palette]
  (apply str
         (for [row rows]
           (str "<line class=\"plotje-rule\" x1=\"" (fmt left) "\" x2=\""
                (fmt right) "\" y1=\"" (fmt (sy (y row))) "\" y2=\""
                (fmt (sy (y row))) "\" stroke=\"" (or stroke (first palette))
                "\" stroke-width=\"" (fmt (or stroke-width 1.5))
                "\" opacity=\"" (fmt (or opacity 0.8)) "\"/>"))))
(defn- tick-svg [rows {:keys [x y stroke opacity stroke-width]} sx sy palette]
  (apply str
         (for [row rows :let [cx (sx (x row)) cy (sy (y row))]]
           (str "<line class=\"plotje-tick\" x1=\"" (fmt cx) "\" x2=\""
                (fmt cx) "\" y1=\"" (fmt (- cy 5.0)) "\" y2=\""
                (fmt (+ cy 5.0)) "\" stroke=\"" (or stroke (first palette))
                "\" stroke-width=\"" (fmt (or stroke-width 2.0))
                "\" opacity=\"" (fmt (or opacity 0.9)) "\"/>"))))
(defn- ticks [values sx y]
  (let [stride (max 1 (quot (+ (count values) 11) 12))]
    (apply str
           (keep-indexed
            (fn [index value]
              (when (zero? (mod index stride))
                (str "<text class=\"plotje-x-tick\" x=\"" (fmt (sx value))
                     "\" y=\"" (fmt y) "\" text-anchor=\"middle\">"
                     (esc (if (keyword? value) (name value) value)) "</text>")))
            values))))

(defn spec->svg [input]
  (let [{:keys [data layers width height title x-label y-label grid? palette]}
        (spec/validate-spec input)
        {:keys [left right top bottom]} margins
        plot-width (- width left right) plot-height (- height top bottom)
        numeric-x? (every? number? (mapcat #(map (:x %) data) layers))
        x-values (vec (distinct (mapcat #(map (:x %) data) layers)))
        y-values (mapcat #(map (:y %) data) layers)
        sx (if numeric-x?
             (linear (extent x-values) left (+ left plot-width))
             (let [slots (zipmap x-values (range)) count (max 1 (count x-values))]
               (fn [x] (+ left (* (+ 0.5 (slots x)) (/ plot-width count))))))
        sy (linear (extent (conj (vec y-values) 0)) (+ top plot-height) top)
        baseline (sy 0)
        bar-width (* 0.72 (/ plot-width (max 1 (count x-values))))]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" width
         "\" height=\"" height "\" viewBox=\"0 0 " width " " height
         "\" role=\"img\" aria-label=\"" (esc (or title "Chart")) "\">"
         "<rect width=\"100%\" height=\"100%\" fill=\"white\"/>"
         (when title (str "<text x=\"" (/ width 2) "\" y=\"26\" text-anchor=\"middle\">"
                          (esc title) "</text>"))
         (when grid?
           (apply str
                  (for [fraction [0.25 0.5 0.75]]
                    (let [y (+ top (* fraction plot-height))]
                      (str "<line class=\"plotje-grid\" x1=\"" left "\" y1=\""
                           (fmt y) "\" x2=\"" (+ left plot-width) "\" y2=\""
                           (fmt y) "\" stroke=\"#d7dde5\"/>")))))
         "<line x1=\"" left "\" y1=\"" baseline "\" x2=\"" (+ left plot-width)
         "\" y2=\"" baseline "\" stroke=\"#444\"/>"
         "<line x1=\"" left "\" y1=\"" top "\" x2=\"" left "\" y2=\""
         (+ top plot-height) "\" stroke=\"#444\"/>"
         (when-not numeric-x? (ticks x-values sx (+ top plot-height 18.0)))
         (apply str (map #(case (:mark %)
                            :line (line-svg data % sx sy palette)
                            :point (point-svg data % sx sy palette)
                            :bar (bar-svg data % sx sy baseline bar-width palette)
                            :area (area-svg data % sx sy baseline palette)
                            :rule (rule-svg data % sy left (+ left plot-width) palette)
                            :tick (tick-svg data % sx sy palette)) layers))
         (when x-label (str "<text x=\"" (+ left (/ plot-width 2)) "\" y=\""
                            (- height 14) "\" text-anchor=\"middle\">"
                            (esc x-label) "</text>"))
         (when y-label (str "<text x=\"18\" y=\"" (+ top (/ plot-height 2))
                            "\" transform=\"rotate(-90 18 " (+ top (/ plot-height 2))
                            ")\" text-anchor=\"middle\">" (esc y-label) "</text>"))
         "</svg>")))
