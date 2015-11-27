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
  ;; I know this seems strange — if I’m only ever creating one task, why
  ;; have a `tasks` var in the plural, containing a vector? Well at first I was creating
  ;; one task per account, so they could sync concurrently. But then I realized that I needed the accounts
  ;; to be either stateful or mutable, because they update their :sync-cursor after each synchronization.
  ;; So I made a quick hack to have a single task that gets all accounts from the DB and then syncs them. This
  ;; is inefficient and unclear so needs to be refactored. So, you know, TODO.
  (let [task (.scheduleAtFixedRate
               executor
               #(doseq [account (db/get-accounts "avi@aviflax.com")]
                 (println "syncing" (-> account :type :name))
                 (try
                   (synch account)
                   (catch Exception e
                     (println "caught exception: " e)))
                 (println "synced" (-> account :type :name)))
               0
               interval-secs
               TimeUnit/SECONDS)]
    (swap! tasks conj task)))

(defn stop-syncing []
  (doseq [task @tasks]
    (.cancel task false))
  (reset! tasks []))
