(ns omnia.core
  (:require [omnia
             [lucene :as lucene]
             [db :as db]
             [accounts :as accounts]]
            [clucy.core :as clucy])
  (:import (java.util.concurrent ScheduledThreadPoolExecutor TimeUnit)))

;; TBD:
;; * should I bother with IDs at this point? (I *think* so…)

(defrecord User [id name])

(defrecord Account-Type [name client-id client-secret])

(defrecord Account [id user-id type-name access-token refresh-token sync-cursor])

(defrecord Document [id name path account-id])

(def executor (ScheduledThreadPoolExecutor. 5))
(def tasks (atom []))

(defn start-syncing []
  (doseq [account (db/get-accounts "avi@aviflax.com")]
    (let [task (.scheduleAtFixedRate
                 executor
                 #(do (println "syncing" (-> account :type :name))
                      (try
                        (accounts/synch account)
                        (catch Exception e
                          (println "caught exception: " e)))
                      (println "synced" (-> account :type :name)))
                 0
                 5
                 TimeUnit/SECONDS)]
      (swap! tasks conj task))))

(defn stop-syncing []
  (doseq [task @tasks]
    (.cancel task false))
  (reset! tasks []))
