(ns omnia.services.box
  "Uses the Box Java SDK for initial sync, but straight HTTP to Box’s API for incremental sync,
   because the Java SDK doesn’t support regular polling for events — only long-polling, which
   doesn’t fit our current needs.

   ## Event Processing

   Note that there’s a cache for recently-processed-events due to this from the Box API docs:

   > Due to timing lag across our multiple datacenters, and our preference of low-latency and
   > insurance to make sure you don’t miss some event, you may receive duplicate events when you
   > call the /events API. You should use the event_id field in each returned event to check for
   > duplicate events and discard ones that you have already processed.

   In practice, I’ve been seeing the same event returned over and over again for successive
   requests. So this is more common than the above quote implies.

   https://box-content.readme.io/reference#events

   ## Security

   Box mainly relies on “collaborators” to share content, but we can’t rely on that because we have
   no way to detect whether _everyone_ in a company has been invited to a folder as a collaborator.

   So instead we rely on their “shared links” feature to determine whether a file or folder is
   accessible to the entire company. Basically that’s just one of the supported settings on a
   “shared link”. So when we first index, we list the root and look for folders in the root that
   have shared links that are accessible to the entire company. Those folders and their entire
   contents are considered eligible for indexing by Omnia, so that’s what gets indexed.

   On incremental syncs, whenever we’re processing a change to a file, we check to ensure that the
   file has one of the shared folders as one of its ancestors; if it does then we index it. If it
   does not then we un-index it.

   We should also be:

   * looking for the creation of new shared-company-wide folders and adding them to the set
   * looking for a folder revision wherein a folder is newly shared company-wide, and adding them
     to the set
   * looking for the deletion or un-sharing of folders and removing them from the set

   BUT we might not get around to those features for awhile.
   "
  (:require [clojure.core.async :refer [chan dropping-buffer go <! >!!]]
            [clojure.core.cache :as cache]
            [clojure.string :refer [join]]
            [omnia
             [db :as db]
             [index :as index]
             [extraction :refer [can-parse?]]]
            [omnia.services.core :as services]
            [omnia.accounts.core :as accounts]
            [pantomime.mime :refer [mime-type-of]]
            [pantomime.extract :refer [parse]]
            [clj-http.client :as client]
            [taoensso.timbre :as log])
  (:import [com.box.sdk BoxAPIConnection BoxUser BoxFolder BoxFile$Info BoxFolder$Info BoxFile BoxAPIException BoxSharedLink$Access]
           [clojure.lang ExceptionInfo]))

(def ^:private recently-processed-events (atom (cache/fifo-cache-factory {})))

(def ^:private auth "TODO: maybe this should just be in the database"
  {:type   :oauth2
   :oauth2 {;; we temporarily need to include a question mark in the start-uri because stupid.
            :start-uri "https://app.box.com/api/oauth2/authorize?foo=bar"
            :token-uri "https://app.box.com/api/oauth2/token"}})

(def ^:private urls {:events "https://api.box.com/2.0/events"})

(defn ^:private get-user [access-token]
  (let [conn (BoxAPIConnection. access-token)
        user (BoxUser/getCurrentUser conn)
        info (.getInfo user (into-array String []))]
    {:id    (.getID info)
     :name  (.getName info)
     :email (.getLogin info)}))

(defrecord Service [slug display-name client-id client-secret]
  services/Service
  (get-auth-uris [_] auth)
  (get-user-account [_ access-token] (get-user access-token)))

(defn ^:private should-index? [file-info]
  true
  ; the below is wrong — rather than checking that a file itself has a shared link with the required
  ; access level, we should check whether a file is rooted in a root-level dir that has such a link.
  ;(= (-> file-info .getSharedLink .getAccess)
  ;   BoxSharedLink$Access/COMPANY)
  )

(defn ^:private build-path [file-info]
  (->> (.getPathCollection file-info)
       next
       (map #(.getName %))
       (join "/")))

(defn ^:private omnia-id-for-file [file-id]
  (str "box/" file-id))

(defn ^:private file->doc [file-info account]
  {:id                 (.getID file-info)
   :name               (.getName file-info)
   :path               (build-path file-info)
   :omnia-id           (omnia-id-for-file (.getID file-info))
   :omnia-account-id   (:id account)
   :omnia-service-name (-> account :service :display-name)})

(defn ^:private get-file-content [file-info]
  (let [file (.getResource file-info)
        stream (ByteArrayOutputStream. (.getSize file-info))]
    (.download file stream)
    (.toByteArray stream)))

(defn ^:private index-file [file-info account]
  (as-> (file->doc file-info account) doc
        (assoc doc :text
                   (when (can-parse? (mime-type-of (.getName file-info)))
                         (-> (get-file-content file-info)
                             parse
                             :text)))
        (index/add-or-update doc)))

(defn ^:private get-new-access-token
  [{:keys [refresh-token], {:keys [client-id client-secret]} :service}]
  (let [url (-> auth :oauth2 :token-uri)
        response (client/post url {:form-params {:client_id     client-id
                                                 :client_secret client-secret
                                                 :grant_type    "refresh_token"
                                                 :refresh_token refresh-token}
                                   :as          :json})]
    (get-in response [:body :access_token])))

(defn ^:private update-access-token [account]
  "Retrieves a new access token for the account, updates the account’s access token in the database,
   returns the account with the new access token."
  (let [token (get-new-access-token account)]
    (println "got new access token" token "; updating account in DB")
    (db/update-account (:id account) :access-token token)
    (assoc account :access-token token)))

(defn ^:private goget
  "Retrieves a resource from the Box API, using the OAuth2 access token in the account. If the
   response indicates that the access token has expired, will automatically retrieve a new access
   token, associate it with the account, save the change to the database, and retry the request.
   Returns a map with two keys: :account and :response. Callers should replace their value for the
   account with the returned value because it might contain a new access token. They don’t, however,
   need to worry about persisting the account to the database; this function handles that if
   necessary. If any error occurs, throws an Exception."
  [url {:keys [access-token] :as account} & [opts]]
  ;; TODO: figure out a way to update the token that is being used in outer contexts
  (try
    {:response (client/get url (assoc opts :oauth-token access-token))
     :account  account}
    (catch ExceptionInfo err
      (if (= (-> err .data :status) 401)
          (as-> (update-access-token account) account
                (goget url account opts))                   ; TODO add something to opts something to ensure that this retry occurs only once
          (throw err)))))

(defn ^:private get-events-resource
  "Returns a map containing the response and the potentially updated account."
  [account stream-position]
  (log/info "Retrieving events for Box account" account "with stream position" stream-position)
  (goget (:events urls) account {:query-params {"stream_position" stream-position
                                                "limit"           10000}
                                 :as           :json}))

(defn ^:private get-latest-event-stream-position [account]
  (-> (get-events-resource account "now") :response :body :next_stream_position str))

(def ^:private event-types-to-process #{"ITEM_CREATE" "ITEM_UPLOAD"
                                        "ITEM_TRASH" "ITEM_UNDELETE_VIA_TRASH"})

(defn un-index-file [file-id account]
  (log/info "un-indexing file" file-id)
  (index/delete {:omnia-account-id (:id account)
                 :omnia-id         (omnia-id-for-file file-id)}))

(defn ^:private recently-processed? [event]
  (let [id (:event_id event)]
    (if (cache/has? @recently-processed-events id)
        (do (swap! recently-processed-events cache/hit id)
            (println "TMP: skipping event because it was already recently processed")
            true)
        (do (swap! recently-processed-events cache/miss id event)
            (println "TMP: NOT skipping event because it was NOT recently processed")
            false))))

(defn ^:private process-events [events account]
  ;; TODO: deal with this from the docs:
  ;; "Events will occasionally arrive out of order. For example a file-upload might show up before
  ;; the Folder-create event. You may need to buffer events and apply them in a logical order."
  ;; TODO: it might be helpful to save the event somewhere, for debugging, auditing, etc.

  (as-> (filter #(event-types-to-process (:event_type %)) events) events
        (filter #(= (-> % :source :type) "file") events)
        (remove recently-processed? events)
        (let [conn (BoxAPIConnection. (:access-token account))]
          (doseq [event events]
            (try
              (let [file (BoxFile. conn (-> event :source :id))]
                (if (= (:event_type event) "ITEM_TRASH")
                    (un-index-file (.getID file) account)
                    (index-file (.getInfo file) account)))
              (catch BoxAPIException e
                ; 404 or 410 means the file was subsequently deleted, so we can just skip it
                (when-not (#{404 410} (.getResponseCode e))
                  (throw e))))))))

(defn ^:private incremental-sync [account]
  (loop [; What our domain model calls a sync-cursor, Box’s API calls the stream position.
         stream-position (:sync-cursor account)
         account account]
    (let [{:keys [account response]} (get-events-resource account stream-position)
          {events               :entries
           next-stream-position :next_stream_position} (:body response)]
      (log/debug "next-stream-position" next-stream-position "events" events)
      (process-events events account)
      (when next-stream-position
            (log/info "Setting Box sync-cursor to " next-stream-position)
            (db/update-account (:id account) :sync-cursor (str next-stream-position)))
      (when (seq events)
            (recur next-stream-position account)))))

(defn ^:private index-folder [conn folder-id account]
  (doseq [item-info (BoxFolder. conn folder-id)]
    (condp #(= (type %2) %1) item-info
      BoxFile$Info
      (when (should-index? item-info)
            (index-file item-info account))

      BoxFolder$Info
      ;; TODO: this recursive call is a bad idea. convert to loop/recur!
      (index-folder conn (.getID item-info) account)

      :skip-other)))

(defn ^:private initial-index [account]
  (log/infof "Starting initial index of account %s" account)
  (index-folder (BoxAPIConnection. (:access-token account))
                "0"
                account)
  (->> (get-latest-event-stream-position account)
       (db/update-account (:id account) :sync-cursor)))

(defn ^:private synchronize! [account]
  ;; TODO: some way to resume an interrupted initial index task
  (if (:sync-cursor account)
      (incremental-sync account)
      (initial-index account)))

(def ^:private sync-chan (chan (dropping-buffer 1)))

(defn ^:private start []
  (go (loop []
        (when-some [account (<! sync-chan)]
          (try (synchronize! account)
               (catch Exception e (log/error e)))
          (recur)))))

(defrecord Account [id user service access-token sync-cursor]
  accounts/Account
  (init [account] account)                                  ; nothing to do right now.
  (sync [account]
    ;; TODO: test that this whole core.async setup does indeed linearize syncs as desired.
    (>!! sync-chan account)))

;; TEMP until I have a centralized “system” start routine
(start)
