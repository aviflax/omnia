(ns omnia.accounts.util
  (:require [omnia.accounts.core :as accounts]
            [omnia.db :as db]
            [omnia.index :as index])
  (:import [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

(defn disconnect
  "Remove the account from the database, all associated documents from the index,
   and let the service know about the disconnect (if it supports it)."
  [account]
  ; TODO: check the return value and validate it
  (db/delete-account account)
  (index/delete-all-docs-for-account account)
  ;; TODO: revoke access for Google Drive as per https://developers.google.com/identity/protocols/OAuth2WebServer#tokenrevoke
  ;; Dropbox doesn’t appear to support this.
  nil)

(defn reset [account]
  (index/delete-all-docs-for-account account)
  (db/update-account account :sync-cursor nil))


(defn sync-all [accounts]
  (doseq [account accounts]
    (print "syncing" (-> account :service :display-name) "...")
    (try
      (accounts/sync account)
      (println "done.")
      (catch Exception e
        (println "caught exception: " e)))))

(def executor (ScheduledThreadPoolExecutor. 5))
(def tasks (atom []))

(defn start-syncing [user-email interval-secs]
  ;; I know this seems strange — if I’m only ever creating one task, why
  ;; have a `tasks` var in the plural, containing a vector? Well at first I was creating
  ;; one task per account, so they could sync concurrently. But then I realized that I needed the accounts
  ;; to be either stateful or mutable, because they update their :sync-cursor after each synchronization.
  ;; So I made a quick hack to have a single task that gets all accounts from the DB and then syncs them. This
  ;; is inefficient and unclear so needs to be refactored. So, you know, TODO.
  (let [task (.scheduleAtFixedRate
               executor
               #(sync-all (db/get-accounts user-email))
               0
               interval-secs
               TimeUnit/SECONDS)]
    (swap! tasks conj task)))

(defn stop-syncing []
  (doseq [task @tasks]
    (.cancel task false))
  (reset! tasks []))
