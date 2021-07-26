(ns billing-app.components.auto-resolvers
  (:require
    [billing-app.model :refer [all-attributes]]
    [mount.core :refer [defstate]]
    [com.fulcrologic.rad.resolvers :as res]
    [com.fulcrologic.rad.database-adapters.sql.resolvers :as sql-res]))

(defstate automatic-resolvers
  :start
  (vec
    (concat
      (res/generate-resolvers all-attributes)
      (sql-res/generate-resolvers all-attributes :billing))))
