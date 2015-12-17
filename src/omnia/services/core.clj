(ns omnia.services.core
  (:require [omnia.services
             [dropbox :as dropbox]
             [google-drive :as gdrive]]
            [omnia
             [index :as index]
             [db :as db]])
  (:import [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

(defmulti synch (fn [account] (-> account :service :slug)))

(defmethod synch "dropbox" [account]
  (println "syncing" account)
  (dropbox/synchronize! account))

(defmethod synch "google-drive" [account]
  (println "syncing" account)
  (gdrive/synchronize! account))

(defmethod synch :default [account]
  (throw (IllegalArgumentException. (str "Unsupported service " (-> account :service :display-name)))))

(defn get-auth [service]
  (condp = (:slug service)
    "dropbox" dropbox/auth
    "google-drive" gdrive/auth))

(defn sync-all [accounts]
  (doseq [account accounts]
    (print "syncing" (-> account :service :display-name) "...")
    (try
      (synch account)
      (println "done.")
      (catch Exception e
        (println "caught exception: " e)))))

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
               #(sync-all (db/get-accounts "avi@aviflax.com"))
               0
               interval-secs
               TimeUnit/SECONDS)]
    (swap! tasks conj task)))

(defn stop-syncing []
  (doseq [task @tasks]
    (.cancel task false))
  (reset! tasks []))
