(ns omnia-poc.core
  (:require [omnia-poc.lucene :as lucene]
            [omnia-poc.db :as db]
            [omnia-poc.dropbox :as dropbox]
            [clucy.core :as clucy])
  (:import (java.util.concurrent ScheduledThreadPoolExecutor TimeUnit)))

(defn search [q]
  (clucy/search lucene/index q 10))

(def sync-task)

(defn start-syncing []
  (def sync-task
    (.scheduleAtFixedRate
      (ScheduledThreadPoolExecutor. 1)
      #(do (dropbox/synchronize! (db/get-source "Dropbox"))
           (println "synced!"))
      0
      5
      TimeUnit/SECONDS)))

(defn stop-syncing []
  (.cancel sync-task false))

(defn -main
  [& args]
  (println "Hello, World!"))
