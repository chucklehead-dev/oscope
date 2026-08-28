(ns oscope.plotje.svg
  "Jolt-portable SVG renderer for oscope's bounded Plotje subset."
  (:require [clojure.string :as str]
            [oscope.plotje.spec :as spec]))

(def ^:private margins {:left 70.0 :right 24.0 :top 46.0 :bottom 58.0})
(defn- esc [x]
  (-> (str (or x "")) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))
(defn- fmt [x] (format "%.2f" (double x)))
(defn- extent [xs]
  (let [low (double (apply min xs)) high (double (apply max xs))]
    (if (= low high) [(- low 0.5) (+ high 0.5)] [low high])))
(defn- linear [[low high] out-low out-high]
  (fn [x] (+ out-low (* (/ (- (double x) low) (- high low))
                          (- out-high out-low)))))
(defn- color-map [rows column palette]
  (zipmap (vec (distinct (map column rows))) (cycle palette)))
(defn- line-svg [rows {:keys [x y color stroke opacity stroke-width]} sx sy palette]
  (let [groups (if color (group-by color rows) {nil rows})
        colors (if color (color-map rows color palette) {nil (or stroke (first palette))})]
    (apply str
           (for [[group group-rows] groups
                 :let [points (->> group-rows (sort-by x)
                                   (map #(str (fmt (sx (x %))) ","
                                              (fmt (sy (y %)))))
                                   (str/join " "))]]
             (str "<polyline fill=\"none\" stroke=\"" (or stroke (colors group))
                  "\" stroke-width=\"" (fmt (or stroke-width 2.0))
                  "\" opacity=\"" (fmt (or opacity 1.0))
                  "\" points=\"" points "\"/>")))))
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
  (let [points (->> rows (sort-by x)
                    (map #(str (fmt (sx (x %))) "," (fmt (sy (y %))))))
        first-x (sx (x (first (sort-by x rows))))
        last-x (sx (x (last (sort-by x rows))))]
    (str "<polygon points=\"" (fmt first-x) "," (fmt baseline) " "
         (str/join " " points) " " (fmt last-x) "," (fmt baseline)
         "\" fill=\"" (or fill (first palette)) "\" fill-opacity=\""
         (fmt (or opacity 0.3)) "\" stroke=\"" (or stroke (first palette))
         "\" stroke-width=\"" (fmt (or stroke-width 2.0)) "\"/>")))
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
