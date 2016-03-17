(ns omnia.core
  (require [omnia [db :as db]
                  [web :as web]]))

(defn start
  "TODO: look into using Component rather than this ad-hoc approximation."
  []
  {:db (db/start "omnia-db")
   :web (web/start)})

(defn stop
  []
  (web/stop)
  (db/stop))
