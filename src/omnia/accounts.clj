(ns omnia.accounts
  (:require [omnia
             [dropbox :as dropbox]
             [google-drive :as gdrive]
             [index :as index]
             [db :as db]]))

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
