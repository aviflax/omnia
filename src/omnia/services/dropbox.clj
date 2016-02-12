(ns omnia.services.dropbox
  (:require [omnia
             [db :as db]
             [index :as index]
             [extraction :refer [can-parse?]]]
            [omnia.accounts.core :as accounts]
            [omnia.services.core :as services]
            [clojure.string :refer [split]]
            [pantomime.mime :refer [mime-type-of]]
            [pantomime.extract :refer [parse]])
  (:import com.dropbox.core.DbxRequestConfig
           [com.dropbox.core.v2 DbxClientV2 DbxFiles$DeletedMetadata DbxFiles$FileMetadata]
           java.util.Locale
           java.io.ByteArrayOutputStream))

(def ^:private auth "TODO: maybe this should just be in the database"
  {:type   :oauth2
   :oauth2 {:start-uri "https://www.dropbox.com/1/oauth2/authorize?require_role=work" ;; TODO: after authorization confirm that the user actually connected a work account
            :token-uri "https://api.dropboxapi.com/1/oauth2/token"}})

(defn ^:private get-client [access-token]
  (DbxClientV2. (DbxRequestConfig. "Omnia" (str (Locale/getDefault)))
                access-token))

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
   :omnia-id           (.pathLower file)                    ; not sure whether/why this needs to be the lowercase form of the path
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
                                     :text
                                     (str (.name entry))    ;; HACK: concatenate the file name to the end of the text so it’ll be included in the search
                                     )))
                (index/add-or-update doc)))
        (println "skipping" (.pathLower entry)))

    ; default case — probably a folder.
    (println "skipping" (.pathLower entry))))

(defn ^:private get-team-folder [client]
  (when-let [folder (->> client
                         .sharing
                         .listFolders
                         .entries
                         (filter #(.isTeamFolder %))
                         first)]
    {:path (.pathLower folder)
     :name (.name folder)
     :id   (.sharedFolderId folder)}))

(defn ^:private init-account [account]
  "Initialize a Dropbox account. Returns a new “version” of the Account record with additional fields set."
  (let [client (get-client (:access-token account))
        {:keys [id name path]} (get-team-folder client)]
    (assoc account
      :team-folder-id id
      :team-folder-name name
      :team-folder-path path)))

(defn ^:private get-user [access-token]
  (as-> (get-client access-token) it
        (.users it)
        (.getCurrentAccount it)
        {:id    (.accountId it)
         :name  (-> it .name .displayName)
         :email (.email it)}))

(defrecord Service [display-name client-id client-secret]
  services/Service
  (get-auth-uris [_] auth)
  (get-user-account [_ access-token] (get-user access-token)))

(defn ^:private synchronize! [{:keys [access-token sync-cursor team-folder-path] :as account}]
  (let [client (get-client access-token)]
    (loop [cursor sync-cursor]
      (let [list-result (if cursor
                            (-> (.files client)
                                (.listFolderContinue cursor))
                            (-> (.files client)
                                (.listFolderBuilder team-folder-path)
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

(defrecord Account
  [id user service access-token sync-cursor team-folder-id team-folder-name team-folder-path]

  accounts/Account
  (init [account] (init-account account))
  (sync [account] (synchronize! account)))
