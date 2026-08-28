(ns oscope.plotje.svg
  "Jolt-portable SVG renderer for oscope's bounded Plotje subset."
  (:require [clojure.string :as str]
            [oscope.plotje.spec :as spec]))

(def ^:private palette ["rgb(228,26,28)" "rgb(55,126,184)"
                        "rgb(77,175,74)" "rgb(152,78,163)"])
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
(defn- color-map [rows column]
  (zipmap (vec (distinct (map column rows))) (cycle palette)))
(defn- line-svg [rows {:keys [x y color]} sx sy]
  (let [groups (if color (group-by color rows) {nil rows})
        colors (if color (color-map rows color) {nil (first palette)})]
    (apply str
           (for [[group group-rows] groups
                 :let [points (->> group-rows (sort-by x)
                                   (map #(str (fmt (sx (x %))) ","
                                              (fmt (sy (y %)))))
                                   (str/join " "))]]
             (str "<polyline fill=\"none\" stroke=\"" (colors group)
                  "\" stroke-width=\"2.00\" points=\"" points "\"/>")))))
(defn- point-svg [rows {:keys [x y color]} sx sy]
  (let [colors (if color (color-map rows color) {})]
    (apply str
           (for [row rows]
             (str "<circle cx=\"" (fmt (sx (x row))) "\" cy=\""
                  (fmt (sy (y row))) "\" r=\"3.00\" fill=\""
                  (if color (colors (color row)) (first palette))
                  "\" fill-opacity=\"0.75\"/>")))))
(defn- bar-svg [rows {:keys [x y]} sx sy baseline bar-width]
  (apply str
         (for [row rows
               :let [center (sx (x row)) endpoint (sy (y row))
                     top (min baseline endpoint) height (abs (- baseline endpoint))]]
           (str "<rect x=\"" (fmt (- center (/ bar-width 2.0)))
                "\" y=\"" (fmt top) "\" width=\"" (fmt bar-width)
                "\" height=\"" (fmt height) "\" fill=\"" (first palette)
                "\" fill-opacity=\"0.75\"/>"))))
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
  (let [{:keys [data layers width height title x-label y-label]}
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
         "<line x1=\"" left "\" y1=\"" baseline "\" x2=\"" (+ left plot-width)
         "\" y2=\"" baseline "\" stroke=\"#444\"/>"
         "<line x1=\"" left "\" y1=\"" top "\" x2=\"" left "\" y2=\""
         (+ top plot-height) "\" stroke=\"#444\"/>"
         (when-not numeric-x? (ticks x-values sx (+ top plot-height 18.0)))
         (apply str (map #(case (:mark %)
                            :line (line-svg data % sx sy)
                            :point (point-svg data % sx sy)
                            :bar (bar-svg data % sx sy baseline bar-width)) layers))
         (when x-label (str "<text x=\"" (+ left (/ plot-width 2)) "\" y=\""
                            (- height 14) "\" text-anchor=\"middle\">"
                            (esc x-label) "</text>"))
         (when y-label (str "<text x=\"18\" y=\"" (+ top (/ plot-height 2))
                            "\" transform=\"rotate(-90 18 " (+ top (/ plot-height 2))
                            ")\" text-anchor=\"middle\">" (esc y-label) "</text>"))
         "</svg>")))
