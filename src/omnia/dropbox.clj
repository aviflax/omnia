(ns omnia.dropbox
  (:require [omnia [db :as db]
             [lucene :as lucene]]
            [clojure.string :refer [lower-case]])
  (:import [com.dropbox.core DbxAppInfo DbxRequestConfig DbxWebAuthNoRedirect DbxClient]
           java.util.Locale
           java.io.ByteArrayOutputStream))

(defn get-req-config []
  (DbxRequestConfig. "Omnia" (str (Locale/getDefault))))

(defn get-auth [{:keys [key secret]}]
  (let [app-info (DbxAppInfo. key secret)]
    (DbxWebAuthNoRedirect. (get-req-config) app-info)))

(defn get-auth-init-url [auth] (.start auth))

(defn get-token [auth code]
  (.accessToken (.finish auth code)))

; TODO: should this be reused?
(defn get-client [{:keys [access-token]}]
  (DbxClient. (get-req-config) access-token))

(defn get-file-content [path client]
  (let [stream (ByteArrayOutputStream.)]
    (.getFile client path nil stream)
    (str stream)))

(defn dropbox-file->omnia-file-with-text
  [client account file]
  (let [f (hash-map :name (.name file)
                    :path (.path file)
                    ;; TODO: include account ID in omnia-file-id so as to ensure uniqueness and avoid conflicts
                    :omnia-file-id (lower-case (.path file)) ; lower-case to work around a possible bug in clucy
                    :omnia-account-id (:id account)
                    :omnia-account-type-name (-> account :type :name))]
    (if (or (.endsWith (.path file) ".txt")                 ; TODO: make this much more sophisticated!
            (.endsWith (.path file) ".md"))
        (assoc f :text (get-file-content (.path file) client))
        f)))

(defn process-delta-entry! [client account entry]
  (if-let [md (.metadata entry)]
    ; TODO: handle directories? I think not — Omnia is about Documents.
    ; TODO: skip files wherein any path segment stars with . — e.g. .svn, .git.
    (when (.isFile md)
      (println (.path md))
      (-> (dropbox-file->omnia-file-with-text client account md)
          lucene/add-or-update-file)
      (Thread/sleep 5))
    (lucene/delete-file {:omnia-account-id (:id account)
                         :omnia-file-id (lower-case (.lcPath entry))})))

(defn synchronize! [{:keys [sync-cursor] :as account}]
  (let [client (get-client account)]
    (loop [cursor sync-cursor]
      (let [delta (.getDelta client cursor)]
        (run! (partial process-delta-entry! client account)
              (.entries delta))

        ; update account cursor in DB
        (db/update-account account :sync-cursor (.cursor delta))

        ; get more
        (when (.hasMore delta)
          (recur (.cursor delta)))))))
