(ns golova.csv
  "Tiny CSV parser. Handles quoted fields with embedded commas, escaped
  quotes (\"\"), CRLF / LF line endings, and a UTF-8 BOM at start."
  (:require [clojure.string :as str]))

(defn- strip-bom [s]
  (if (and (pos? (count s)) (= 0xfeff (.charCodeAt s 0)))
    (subs s 1)
    s))

(defn parse
  "Parse CSV text into {:headers [...] :rows [[...]]}. The first non-empty
  row is treated as headers; subsequent rows are aligned to it (shorter rows
  are padded with \"\")."
  [text]
  (let [text (strip-bom (or text ""))
        n (count text)]
    (loop [i 0
           field []
           row []
           rows []
           in-quote? false]
      (if (>= i n)
        ;; flush trailing field + row
        (let [row (conj row (apply str field))
              empty-row? (and (= 1 (count row)) (str/blank? (first row)))
              rows (if empty-row? rows (conj rows row))
              [headers data] (if (seq rows) [(first rows) (vec (rest rows))]
                                            [[] []])
              w (count headers)
              data (mapv #(vec (take w (concat % (repeat ""))))
                         data)]
          {:headers headers :rows data})
        (let [c (.charAt text i)]
          (cond
            in-quote?
            (cond
              (= c "\"")
              (if (and (< (inc i) n) (= "\"" (.charAt text (inc i))))
                ;; escaped quote ("")
                (recur (+ i 2) (conj field "\"") row rows true)
                ;; closing quote
                (recur (inc i) field row rows false))
              :else
              (recur (inc i) (conj field c) row rows true))

            (= c "\"")
            (recur (inc i) field row rows true)

            (= c ",")
            (recur (inc i) [] (conj row (apply str field)) rows false)

            (or (= c "\n") (= c "\r"))
            (let [next-i (if (and (= c "\r")
                                  (< (inc i) n)
                                  (= "\n" (.charAt text (inc i))))
                           (+ i 2) (inc i))
                  row (conj row (apply str field))
                  empty-row? (and (= 1 (count row)) (str/blank? (first row)))
                  rows (if empty-row? rows (conj rows row))]
              (recur next-i [] [] rows false))

            :else
            (recur (inc i) (conj field c) row rows false)))))))
