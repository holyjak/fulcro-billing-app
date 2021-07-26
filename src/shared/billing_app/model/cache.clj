(ns billing-app.model.cache
  "In prod, cache data in memory for a while. In dev, also store them to a file so the app can be developed offline."
  (:require
    [clojure.core.cache.wrapped :as cache]
    [clojure.core.cache :as cache-raw]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [clojure.java.shell :as sh]
    [taoensso.timbre :as log])
  (:import (java.net URLEncoder)))

(def results-caches (atom nil))

(defn cache-for
  "Get (or make) cache for the given function"
  [fn-var]
  (let [ttl (or (-> fn-var meta :ttl-ms)
                (.toMillis java.util.concurrent.TimeUnit/MINUTES 60))]
    (or (get @results-caches fn-var)
        (get (swap! results-caches assoc fn-var
                    (-> (cache-raw/lru-cache-factory {} :threshold 32)
                        (cache-raw/ttl-cache-factory :ttl ttl)
                        (atom)))
             fn-var))))

(defn lookup-or-miss
  "Return (possibly cached) value of `val-fn`, caching it for the next time."
  [fn-var key val-fn]
  (cache/lookup-or-miss
    (cache-for fn-var)
    key
    (fn [_] (val-fn))))

(defn- cached [path]
  (let [f    (io/file path)]
    (when (.canRead f)
      (try (clojure.edn/read-string
             (slurp f))
           (catch Exception e
             (throw (ex-info (str "Failed to read cached data from '" path "' due to " e) {:file f, :path path})))))))

(defn ensure-persistable [x]
  (walk/postwalk
    #(cond-> % (sequential? %) (vec))
    x))

(defn cache-to-fs? []
  (= "dev" (System/getenv "APP_ENV")))

(defn file-path [fn-name args]
  (let [key-in  (-> args
                    (->> (map #(if (fn? %)
                                 (str/replace (str %) #"@\w+$" "")
                                 %))
                         (clojure.string/join "-"))
                    (str/replace #"[ {}\[\]#/\"':]" "_")
                    (str/replace #"_+" "_"))
        key-enc (URLEncoder/encode key-in "ascii")]
    (str "dev-data-cache/" fn-name "-" key-enc ".edn")))

(defn var->base-cache-key
  "Turn the var `my.helpers/myfn` into \"helpers:myfn\""
  [fn-var]
  (let [{:keys [ns name]} (meta fn-var)
        ns-part (-> (str ns)
                    (str/split #"\.")
                    last)]
    (str ns-part ":" name)))

(defn file-path-for [fn-var env+args]
  (let [base-cache-key (var->base-cache-key fn-var)
        cache-key-args (rest env+args)]
    (file-path base-cache-key cache-key-args)))

(defn caching
  "Execute `(fn-var args...)`, storing the result into a cache and returning it upon the next invocation (based on the 2nd+ args).

  Similar to `memoize` but *excludes the first argument from the cache key* (because it is supposed to be an 'environment' argument
  such as a datasource) and uses a size- and time-limited cache.

  During development, it also caches the data permanently to the directory `./dev-data-cache/`.
  By default values are cached for 60 minutes but you can change that by adding `:ttl-ms <miliseconds>` to the function's metadata.

  Tips: Use [[evict-all-for]] to clear it (+ remove any cache files from dev-data-cache/).

  BEWARE: Pass in a var, i.e. #'my-ns/my-fn"
  ([fn-var & env+args]
   {:pre [(var? fn-var)]}
   (let [fpath          (file-path-for fn-var env+args)
         cached-data    (cached fpath)
         res            (lookup-or-miss
                          fn-var
                          fpath
                          #(or
                             (and (cache-to-fs?) cached-data)
                             (apply fn-var env+args)))]
     (when (cache-to-fs?)
       (if cached-data
         (log/info "cached-data for" fpath (boolean cached-data))
         (do
           (log/info "Dev mode => storing results in the file " fpath)
           (binding [*print-level* nil, *print-length* nil]
             (io/make-parents fpath)
             (spit fpath (pr-str (ensure-persistable res)))))))
     res)))

(defn evict-all-for
  "Remove all cached results for the given fn.
  To evict for all functions, simply remove the `./dev-data-cache/` folder and either
  `(reset! results-caches nil)` or re-evaluate its `def`."
  [fn-var]
  {:pre [(var? fn-var)]}
  (let [cache     (get @results-caches fn-var)
        cnt       (some-> cache deref count)
        file-glob (str (str/replace (file-path-for fn-var nil) #"\.edn$" "") "*.edn")
        {:keys [exit out err]} (when (and file-glob cache-to-fs?)
                                 (sh/sh "sh" "-c" (str "rm -v " file-glob)))
        fs-msg    (if (zero? exit)
                    (str/split-lines out)
                    (str "NONE due to: " err))]
    (if cache
      (do (swap! cache #(reduce cache-raw/evict % (keys %)))
          {:evicted cnt :fs/deleted fs-msg})
      {:evicted 0 :reason :not-found :fs/deleted fs-msg})))

(comment
  (count @results-caches)
  (do
    (set! *print-length* 10)
    (set! *print-level* 3))
  (->> (get @results-caches #'some.ns/some-function)
       deref
       vals
       first)
  (cache-for #'ls)

  (def env {:com.fulcrologic.rad.database-adapters.sql/connection-pools
            billing-app.components.connection-pools/connection-pools})

  (caching #'billing-app.components.database-queries/get-invoice-employees env 450)
  (caching #'billing-app.components.database-queries/get-latest-organizations :env {})

  (evict-all-for #'billing-app.components.fake-domain-client/leasing-devices)

  (-> (clojure.core.cache/lru-cache-factory {} :threshold 32)
      (cache/ttl-cache-factory :ttl 5000))
  nil)
