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
  (println "Syncing accounts:" accounts)
  (doseq [account accounts]
    (print "Syncing" (-> account :service :display-name) "...")
    (try
      (accounts/sync account)
      (println "done.")
      (catch Exception e
        (println "caught exception: " e)))))

(def executor (atom nil))

(defn start-syncing [user-email interval-secs]
  (let [exec (ScheduledThreadPoolExecutor. 1)]
    (reset! executor exec)
    (.scheduleAtFixedRate
      exec
      #(sync-all (db/get-accounts user-email))
      0
      interval-secs
      TimeUnit/SECONDS)))

(defn stop-syncing []
  (.shutdown @executor))
