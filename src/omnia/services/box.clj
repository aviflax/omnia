(ns omnia.services.box
  (:require [omnia
             [db :as db]
             [index :as index]
             [extraction :refer [can-parse?]]]
            [omnia.services.core :as services]
            [omnia.accounts.core :as accounts]
            [pantomime.mime :refer [mime-type-of]]
            [pantomime.extract :refer [parse]])
  (:import [com.box.sdk BoxAPIConnection BoxUser BoxFolder BoxFile$Info BoxFolder$Info]
           [java.io ByteArrayOutputStream]))

(def ^:private auth "TODO: maybe this should just be in the database"
  {:type   :oauth2
   :oauth2 {;; we temporarily need to include a question mark in the start-uri because stupid.
            :start-uri "https://app.box.com/api/oauth2/authorize?foo=bar"
            :token-uri "https://app.box.com/api/oauth2/token"}})

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

(defn ^:private incremental-sync [account]
  ;; TODO
  nil)

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
  (let [conn (BoxAPIConnection. (:access-token account))]
    (index-folder conn "0" account)))

(defn ^:private synchronize! [account]
  (if (:sync-cursor account)
      (incremental-sync account)
      (initial-index account)))

(defrecord Account
  [id user service access-token sync-cursor]

  accounts/Account

  (init [account]
    ; nothing to do right now.
    account)

  (sync [account]
    (synchronize! account)))
