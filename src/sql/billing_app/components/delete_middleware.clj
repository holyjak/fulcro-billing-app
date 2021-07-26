(ns billing-app.components.delete-middleware
  (:require
    [com.fulcrologic.rad.database-adapters.sql.middleware :as sql-middleware]))

(def middleware (sql-middleware/wrap-sql-delete))
