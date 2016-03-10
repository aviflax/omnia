(ns omnia.services.box
  "Uses the Box Java SDK for initial sync, but straight HTTP to Box’s API for incremental sync,
   because the Java SDK doesn’t support regular polling for events — only long-polling, which
   doesn’t fit our current needs."
  (:require [clojure.core.async :refer [chan dropping-buffer go <! >!!]]
            [omnia
             [db :as db]
             [index :as index]
             [extraction :refer [can-parse?]]]
            [omnia.services.core :as services]
            [omnia.accounts.core :as accounts]
            [pantomime.mime :refer [mime-type-of]]
            [pantomime.extract :refer [parse]]
            [clj-http.client :as client]
            [taoensso.timbre :as timbre
             :refer (log trace debug info warn error fatal report
                         logf tracef debugf infof warnf errorf fatalf reportf
                         spy get-env log-env)])
  (:import [com.box.sdk BoxAPIConnection BoxUser BoxFolder BoxFile$Info BoxFolder$Info]
           [java.io ByteArrayOutputStream]))

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

(defn ^:private should-index? [file-info] true)

(defn ^:private get-path [file-info] "COMING SOON!")

(defn ^:private file->doc [file-info account]
  {:id                 (.getID file-info)
   :name               (.getName file-info)
   :path               (get-path file-info)
   :omnia-id           (str "box/" (.getID file-info))
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
  (let [token (get-new-access-token account)]
    (println "got new access token" token "; updating account in DB")
    (db/update-account account :access-token token)
    (assoc account :access-token token)))

(defn ^:private goget
  [url {:keys [access-token] :as account} & [opts]]
  ;; TODO: figure out a way to update the token that is being used in outer contexts
  (try
    (client/get url (assoc opts :oauth-token access-token))
    (catch Exception err
      (if (= (:status err) 401)
          (as-> (update-access-token account) account
                (client/get url (assoc opts :oauth-token (:access-token account))))
          (throw err)))))

(defn ^:private get-events-resource [account stream-position]
  (info "Retrieving events for Box account" account "with stream position" stream-position)
  (-> (goget (:events urls) account {:query-params {"stream_position" stream-position
                                                    "limit"           10000}
                                     :as           :json})
      :body))

(defn ^:private get-latest-event-stream-position [account]
  (-> (get-events-resource account "now")
      :next_stream_position))

(def ^:private event-types-to-process #{"ITEM_CREATE" "ITEM_UPLOAD" "ITEM_TRASH"})

(defn un-index-file [a b] nil) ;TODO

(defn ^:private process-events [events account]
  ;; TODO: deal with this from the docs:
  ;; "Events will occasionally arrive out of order. For example a file-upload might show up before
  ;; the Folder-create event. You may need to buffer events and apply them in a logical order."
  ;; TODO: it might be helpful to save the event somewhere, for debugging, auditing, etc.

  (as-> (filter #(event-types-to-process (:event_type %)) events) events
        (filter #(= (-> % :source :type) "file") events)
        (doseq [event events]
          (let [file-info "TODO"]
            (if (not= (:event_type event) "ITEM_TRASH")
                (index-file file-info account)
                (un-index-file file-info account))))))

(defn ^:private incremental-sync [account]
  (loop [; What our domain model calls a sync-cursor, Box’s API calls the stream position.
         stream-position (:sync-cursor account)]
    (let [{events               :entries
           next-stream-position :next_stream_position} (get-events-resource account stream-position)]
      (process-events events account)
      (db/update-account account :sync-cursor next-stream-position)
      (when (seq events)
            (recur next-stream-position)))))

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
  (infof "Starting initial index of account %s" account)
  (index-folder (BoxAPIConnection. (:access-token account))
                "0"
                account)
  (->> (get-latest-event-stream-position account)
       (db/update-account account :sync-cursor)))

(defn ^:private synchronize! [account]
  ;; TODO: some way to resume an interrupted initial index task
  (if (:sync-cursor account)
      (incremental-sync account)
      (initial-index account)))

(def ^:private sync-chan (chan (dropping-buffer 1)))

(defn ^:private start []
  (go
    (loop []
      (when-some [account (<! sync-chan)]
        (synchronize! account)
        (recur)))))

(defrecord Account [id user service access-token sync-cursor]
  accounts/Account
  (init [account] account)                                  ; nothing to do right now.
  (sync [account]
    ;; TODO: test that this whole core.async setup does indeed linearize syncs as desired.
    (>!! sync-chan account)))

;; TEMP until I have a centralized “system” start routine
(start)
