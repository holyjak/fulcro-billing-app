(ns billing-app.utils
  (:require
    [clojure.string :as str]))

(defn sort-by-match
  "Search for `needle` in each `prop` of the given `coll` and return
  call with entries that start with `needle` first, those including it
  second, those without a match last."
  [needle propfn coll]
  (let [matcher
        #(let [value (propfn %)]
           (cond
             (not (string? needle)) :unmatched
             (< (count needle) 3) :unmatched
             (str/starts-with? value needle) :best
             (str/includes? value needle) :ok
             :else :unmatched))

        {:keys [best ok unmatched]}
        (group-by matcher coll)

        matches (concat best ok)]
    (concat matches (when (empty? matches) unmatched))))

#?(:clj (defn inst->local-date ^java.time.LocalDate [^java.util.Date inst]
          (-> inst
              (.getTime)
              (java.time.Instant/ofEpochMilli)
              (java.time.LocalDate/ofInstant (java.time.ZoneId/systemDefault)))))
