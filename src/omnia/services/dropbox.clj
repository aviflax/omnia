(ns omnia.services.dropbox
  (:require [omnia
             [db :as db]
             [index :as index]]
            [omnia.extraction :refer [can-parse?]]
            [clojure.string :refer [split]]
            [pantomime.mime :refer [mime-type-of]]
            [pantomime.extract :refer [parse]])
  (:import [com.dropbox.core DbxAppInfo DbxRequestConfig DbxWebAuthNoRedirect]
           [com.dropbox.core.v2 DbxClientV2 DbxFiles$DeletedMetadata DbxFiles$FileMetadata]
           java.util.Locale
           java.io.ByteArrayOutputStream))

(def auth "TODO: maybe this should just be in the database"
  {:type   :oauth2
   :oauth2 {:start-uri "https://www.dropbox.com/1/oauth2/authorize?require_role=work" ;; TODO: after authorization confirm that the user actually connected a work account
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
  (DbxClientV2. (get-req-config) access-token))

(defn ^:private get-content [path client]
  ;; TODO: using a byte array could be problematic if/when dealing with very large files
  (let [download-builder (-> (.files client)
                             (.downloadBuilder path))
        stream (ByteArrayOutputStream.)]
    (.run download-builder stream)
    (.toByteArray stream)))

(defn ^:private should-index? [metadata-entry]
  (not (some #(.startsWith % ".")
             (split (.pathLower metadata-entry) #"/"))))

(defn ^:private file->doc
  "Convert a Dropbox file to an Omnia document."
  [account file]
  {:id                 (.id file)
   :name               (.name file)
   :path               (.pathLower file)
   ;; TODO: include account ID in omnia-id so as to ensure uniqueness and avoid conflicts
   :omnia-id           (.pathLower file)
   :omnia-account-id   (:id account)
   :omnia-service-name (-> account :service :display-name)})

(defn ^:private process-list-entry! [client account entry]
  (condp #(= (type %2) %1) entry

    DbxFiles$DeletedMetadata
    (index/delete {:name             (.name entry)
                   :omnia-account-id (:id account)
                   :omnia-id         (.pathLower entry)})

    DbxFiles$FileMetadata
    (if (should-index? entry)
        (do
          (println "indexing" (.pathLower entry))
          (as-> (file->doc account entry) doc
                (assoc doc :text
                           (when (can-parse? (mime-type-of (.name entry)))
                                 (-> (get-content (.pathLower entry) client)
                                     parse
                                     :text)))
                (index/add-or-update doc)))
        (println "skipping" (.pathLower entry)))

    (println "skipping" (.pathLower entry))))

(defn synchronize! [{:keys [sync-cursor] :as account}]
  (let [client (get-client account)]
    (loop [cursor sync-cursor]
      (let [list-result (if cursor
                            (-> (.files client)
                                (.listFolderContinue cursor))
                            (-> (.files client)
                                (.listFolderBuilder "")
                                (.recursive true)
                                .start))]
        (run! (partial process-list-entry! client account)
              (.entries list-result))

        ; update account cursor in DB
        (db/update-account account :sync-cursor (.cursor list-result))

        (Thread/sleep 5)

        ; get more
        (when (.hasMore list-result)
              (recur (.cursor list-result)))))))
