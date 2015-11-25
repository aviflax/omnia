(ns omnia.accounts
  (:require [omnia
             [dropbox :as dropbox]
             [google-drive :as gdrive]
             [index :as index]
             [db :as db]])
  (:import [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

(defmulti synch (fn [account] (-> account :type :name)))

(defmethod synch "Dropbox" [account]
  (dropbox/synchronize! account))

(defmethod synch "Google Drive" [account]
  (gdrive/synchronize! account))

(defmethod synch :default [account]
  (throw (IllegalArgumentException. (str "Unsupported account type " (-> account :type :name)))))

(defn reset [account]
  (index/delete-all-docs-for-account account)
  (db/update-account account :sync-cursor nil))

(def executor (ScheduledThreadPoolExecutor. 5))
(def tasks (atom []))

(defn start-syncing [interval-secs]
  (doseq [account (db/get-accounts "avi@aviflax.com")]
    (let [task (.scheduleAtFixedRate
                 executor
                 #(do (println "syncing" (-> account :type :name))
                      (try
                        (synch account)
                        (catch Exception e
                          (println "caught exception: " e)))
                      (println "synced" (-> account :type :name)))
                 0
                 interval-secs
                 TimeUnit/SECONDS)]
      (swap! tasks conj task))))

(defn stop-syncing []
  (doseq [task @tasks]
    (.cancel task false))
  (reset! tasks []))
