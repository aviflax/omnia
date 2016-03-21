(ns omnia.services.dropbox
  (:require [omnia
             [db :as db]
             [index :as index]
             [extraction :refer [can-parse?]]]
            [omnia.accounts.core :as accounts]
            [omnia.services.core :as services]
            [clojure.string :refer [join last-index-of split]]
            [pantomime.mime :refer [mime-type-of]]
            [pantomime.extract :refer [parse]])
  (:import com.dropbox.core.DbxRequestConfig
           [com.dropbox.core.v2 DbxClientV2]
           java.util.Locale
           java.io.ByteArrayOutputStream
           [com.dropbox.core.v2.files FileMetadata DeletedMetadata]))

(def ^:private auth "TODO: maybe this should just be in the database"
  {:type   :oauth2
   :oauth2 {:start-uri "https://www.dropbox.com/1/oauth2/authorize?require_role=work" ;; TODO: after authorization confirm that the user actually connected a work account
            :token-uri "https://api.dropboxapi.com/1/oauth2/token"}})

(defn ^:private get-client [access-token]
  (DbxClientV2. (DbxRequestConfig. "Omnia" (str (Locale/getDefault)))
                access-token))

(defn ^:private get-content [path client]
  ;; TODO: using a byte array could be problematic if/when dealing with very large files
  (let [downloader (-> (.files client)
                             (.download path))
        stream (ByteArrayOutputStream.)]
    (.download downloader stream)
    (.toByteArray stream)))

(defn ^:private should-index? [metadata-entry]
  (not (some #(.startsWith % ".")
             (split (.getPathLower metadata-entry) #"/"))))

(defn ^:private prep-path
  "Prep a Dropbox path for indexing by removing the last segment (the file name) and the leading
   slash, so it then conforms to what the rest of Omnia expects."
  [path]
  (subs path 1 (last-index-of path "/")))

(defn ^:private file->doc
  "Convert a Dropbox file to an Omnia document."
  [account file]
  {:id                 (.getId file)
   :name               (.getName file)
   :path               (prep-path (.getPathDisplay file))
   ;; TODO: include account ID in omnia-id so as to ensure uniqueness and avoid conflicts
   :omnia-id           (.getPathLower file)                    ; not sure whether/why this needs to be the lowercase form of the path
   :omnia-account-id   (:id account)
   :omnia-service-name (-> account :service :display-name)})

(defn ^:private process-list-entry! [client account entry]
  (condp #(= (type %2) %1) entry

    DeletedMetadata
    (index/delete {:name             (.getName entry)
                   :omnia-account-id (:id account)
                   :omnia-id         (.getPathLower entry)})

    FileMetadata
    (when (should-index? entry)
          (as-> (file->doc account entry) doc
                (assoc doc :text
                           (when (can-parse? (mime-type-of (.getName entry)))
                                 (-> (get-content (.getPathLower entry) client)
                                     parse
                                     :text
                                     (str (.getName entry))    ;; HACK: concatenate the file name to the end of the text so it’ll be included in the search
                                     )))
                (index/add-or-update doc)))

    ; default case — probably a folder.
    (println "skipping" (.getPathLower entry))))

(defn ^:private get-team-folder [client]
  (when-let [folder (->> client
                         .sharing
                         .listFolders
                         .getEntries
                         (filter #(.getIsTeamFolder %))
                         first)]
    {:path (.getPathLower folder)
     :name (.getName folder)
     :id   (.getSharedFolderId folder)}))

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
        {:id    (.getAccountId it)
         :name  (-> it .getName .getDisplayName)
         :email (.getEmail it)}))

(defrecord Service [slug display-name client-id client-secret]
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
                                (.withRecursive true)
                                .start))]
        (run! (partial process-list-entry! client account)
              (.getEntries list-result))

        ; update account cursor in DB
        (db/update-account (:id account) :sync-cursor (.getCursor list-result))

        (Thread/sleep 5)

        ; get more
        (when (.getHasMore list-result)
              (recur (.getCursor list-result)))))))

(defrecord Account
  [id user service access-token sync-cursor team-folder-id team-folder-name team-folder-path]

  accounts/Account
  (init [account] (init-account account))
  (sync [account] (synchronize! account)))
