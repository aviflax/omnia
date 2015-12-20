(ns omnia.services.dropbox
  (:require [omnia
             [db :as db]
             [index :as index]]
            [omnia.extraction :refer [can-parse?]]
            [clojure.string :refer [lower-case split]]
            [pantomime.mime :refer [mime-type-of]]
            [pantomime.extract :refer [parse]])
  (:import [com.dropbox.core DbxAppInfo DbxRequestConfig DbxWebAuthNoRedirect]
           [com.dropbox.core.v1 DbxClientV1]
           java.util.Locale
           java.io.ByteArrayOutputStream))

(def auth "TODO: maybe this should just be in the database"
  {:type   :oauth2
   :oauth2 {:start-uri "https://www.dropbox.com/1/oauth2/authorize?" ;; stupid but whatever
            :token-uri "https://api.dropboxapi.com/1/oauth2/token"}})

(defn ^:private get-req-config []
  (DbxRequestConfig. "Omnia" (str (Locale/getDefault))))

(defn ^:private get-auth [{:keys [key secret]}]
  (let [app-info (DbxAppInfo. key secret)]
    (DbxWebAuthNoRedirect. (get-req-config) app-info)))

(defn ^:private get-auth-init-url [auth] (.start auth))

(defn ^:private get-token [auth code]
  (.accessToken (.finish auth code)))

; TODO: should this be reused?
(defn ^:private get-client [{:keys [access-token]}]
  (DbxClientV1. (get-req-config) access-token))

(defn ^:private get-content [path client]
  ;; TODO: using a byte array could be problematic if/when dealing with very large files
  (let [stream (ByteArrayOutputStream.)]
    (.getFile client path nil stream)
    (.toByteArray stream)))

(defn ^:private should-index? [metadata-entry]
  (and (.isFile metadata-entry)
       (not (some #(.startsWith % ".")
                  (split (.path metadata-entry) #"/")))))

(defn ^:private file->doc
  "Convert a Dropbox file to an Omnia document."
  [account file]
  {:name               (.name file)
   :path               (.path file)
   ;; TODO: include account ID in omnia-id so as to ensure uniqueness and avoid conflicts
   :omnia-id           (lower-case (.path file))            ; lower-case to work around a possible bug in clucy
   :omnia-account-id   (:id account)
   :omnia-service-name (-> account :service :display-name)})

(defn ^:private process-delta-entry! [client account entry]
  (if-let [md (.metadata entry)]
    (if (should-index? md)
        (do
          (println "indexing" (.path md))
          (as-> (file->doc account md) doc
                (assoc doc :text
                           (when (can-parse? (mime-type-of (.name md)))
                                 (-> (get-content (.path md) client)
                                     parse
                                     :text)))
                (index/add-or-update doc)))
        (println "skipping" (.path md)))
    (index/delete {:omnia-account-id (:id account)
                   :omnia-id         (lower-case (.lcPath entry))})))

(defn synchronize! [{:keys [sync-cursor] :as account}]
  (let [client (get-client account)]
    (loop [cursor sync-cursor]
      (let [delta (.getDelta client cursor)]
        (run! (partial process-delta-entry! client account)
              (.entries delta))

        ; update account cursor in DB
        (db/update-account account :sync-cursor (.cursor delta))

        (Thread/sleep 5)

        ; get more
        (when (.hasMore delta)
              (recur (.cursor delta)))))))
