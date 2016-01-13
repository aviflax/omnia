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

(defprotocol Account
  (init [account] "Initialize an Account quickly and synchronously.")
  ;(connect [account])
  ;(get-doc-changes [account])
  ;(disconnect [account])
  )

(defn map->Account [account-map]
  ; Something less dynamic (e.g. a direct call to dropbox/map->Account etc using multimethods)
  ; would probably be simpler, but it would involve cycles.
  ; So this is my semi-crazy approach to avoiding cyclical
  ; dependencies at compile time. Probably not a great idea but working for now.
  (let [ns-sym (symbol (str "omnia.services." (:service-slug account-map)))
        _ (require ns-sym)
        factory @(ns-resolve ns-sym 'map->Account)]
    (factory account-map)))
