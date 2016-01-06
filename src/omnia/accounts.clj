(ns omnia.accounts
  (:require [omnia
             [db :as db]
             [index :as index]]))

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

(defprotocol Account                                        ;; TODO: rename to Account, maybe move to ns accounts
  (init [account])
  ;(connect [account])
  ;(get-doc-changes [account])
  ;(disconnect [account])
  )

(defmulti map->Account #(:service-slug %))

(defmethod map->Account "dropbox" [m]
  ; This would be much simpler: (dropbox/map->DropboxAccount m)
  ; But this crazy thing is my current approach to avoiding cyclical
  ; dependencies at compile time. Probably not a great idea but working for now…?
  (let [ns-sym (symbol "omnia.services.dropbox")
        _ (require ns-sym)
        factory @(ns-resolve ns-sym 'map->DropboxAccount)]
    (factory m)))

(defmethod map->Account "google-drive" [m]
  ; This crazy thing is my current approach to avoiding cyclical
  ; dependencies at compile time. Probably not a great idea but working for now…?
  (let [ns-sym (symbol "omnia.services.google-drive")
        _ (require ns-sym)
        factory @(ns-resolve ns-sym 'map->GoogleDriveAccount)]
    (factory m)))
