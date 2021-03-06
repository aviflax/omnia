(ns omnia.services.google-drive
  "If might seem odd that this ns uses GDrive’s REST API directly via HTTP
   rather than employing GDrive’s Java SDK that should hypothetically provide
   a more convenient way to integrate with GDrive, similar to the Dropbox case.
   The reason is that GDrive’s Java SDK is an unusable piece of crap. It’s classic
   awful Java: millions of classes and factories and builders and awful docs on
   how to actually *use* all that crap."
  (:require [omnia
             [db :as db]
             [index :as index]]
            [omnia.accounts.core :as accounts]
            [omnia.services.core :as services]
            [omnia.extraction :refer [can-parse?]]
            [clojure.string :refer [blank? lower-case]]
            [ring.util.codec :refer [url-encode]]
            [pantomime.extract :refer [parse]]
            [clj-http.client :as client])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private auth "TODO: maybe this should just be in the database"
  {:type   :oauth2
   :oauth2 {:start-uri (str "https://accounts.google.com/o/oauth2/v2/auth?"
                            "scope=" (url-encode "openid profile email https://www.googleapis.com/auth/drive.readonly")
                            "&access_type=offline")
            :token-uri "https://www.googleapis.com/oauth2/v4/token"}})

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
    (db/update-account (:id account) :access-token token)
    (assoc account :access-token token)))

(defn ^:private goget
  [url {:keys [access-token] :as account} & [opts]]
  ;; TODO: figure out a way to update the token that is being used in outer contexts
  (try
    (client/get url (assoc opts :oauth-token access-token))
    (catch ExceptionInfo err
      (if (= (-> err .data :status) 401)
          (as-> (update-access-token account) account
                (goget url account opts))
          (throw err)))))

(defn ^:private file->doc
  "Convert a Google Drive file to an Omnia document."
  [account file]
  (assoc file :name (:title file)
              :path nil                                     ; TODO: add path, if not toooo much of a hassle
              :mime-type (:mimeType file)
              :omnia-id (lower-case (:id file))             ; lower-case to work around a bug in clucy
              :omnia-account-id (:id account)
              :omnia-service-name (-> account :service :display-name)) ; TODO: might not make sense to store this here; I can get it by reference via the account ID
  )

(defn ^:private get-google-doc-content [file account]
  (let [download-url (or (get-in file [:exportLinks (keyword "text/plain")])
                         (get-in file [:exportLinks (keyword "application/pdf")])
                         nil)]
    (if download-url
        (-> (goget download-url account {:as :stream})
            :body)
        (throw (Exception. "No download URL found")))))

(defn ^:private get-file-content [file account]
  (-> (str "https://www.googleapis.com/drive/v2/files/" (:id file))
      (goget account
             {:query-params {"alt" "media"}
              ; make sure to close the stream after reading it!
              :as           :stream})
      ; returns a stream (I guess it’s an InputStream...?)
      :body))

(defn ^:private get-content
  "Returns a stream."
  [file account]
  (if (empty? (:exportLinks file))
      (get-file-content file account)
      (get-google-doc-content file account)))

(defn ^:private get-changes [account cursor]
  (goget "https://www.googleapis.com/drive/v2/changes"
         account
         {:as           :json
          :query-params (merge
                          {"includeDeleted" "true"
                           "maxResults"     1000
                           ; apparently `fields` is required if we want resulting items to be “full”, and we do, because otherwise we don’t get the “permissions” property
                           "fields"         "items,largestChangeId,nextLink"}
                          (when-not (blank? cursor)
                            {"pageToken" (-> cursor bigint int)}))}))

(defn ^:private should-index? [file account]
  ;; TODO: Fix hard-coded domain!
  (boolean
    (and (not (= (:mimeType file) "application/vnd.google-apps.folder"))
         (not (-> file :labels :trashed))
         (some #(and (= (:type %) "domain")
                     (= (:domain %) "flaxfamily.com"))
               (or (:permissions file) [])))))

(defn ^:private process-change-item! [account item]
  (cond
    (:deleted item)
    (index/delete {:omnia-account-id (:id account)
                   :omnia-id         (lower-case (:fileId item))})

    (should-index? (:file item) account)
    (let [file (:file item)]
      (println (:title file))
      (-> (file->doc account file)
          (assoc :text
                 (when (or (can-parse? (:mimeType file))
                           (not (empty? (:exportLinks file)))) ; if exportLinks has values, it’s probably a Google Docs file, e.g. Google Docs or Google Sheets
                       (-> (get-content file account)
                           parse
                           :text)))
          index/add-or-update))))

(defn ^:private next-cursor-from-changes
  [changes]
  ;; TODO: handle exceptions, no matches found, etc.
  (when (not (blank? (:nextLink changes)))
        (-> (re-seq #"pageToken=(\d+)" (:nextLink changes))
            first
            second)))

(defn synchronize! [account]
  (loop [cursor (:sync-cursor account)]
    (let [response (get-changes account cursor)
          changes (:body response)]
      (when (not= (:status response) 200)
            (throw (Exception. "ruh roh")))

      (run! (partial process-change-item! account)
            (:items changes))

      ; get more
      (if-let [next-cursor (next-cursor-from-changes changes)]
        (do
          (db/update-account (:id account) :sync-cursor (str next-cursor))
          (Thread/sleep 10)
          (recur next-cursor))
        (db/update-account (:id account) :sync-cursor (-> changes :largestChangeId bigint int inc str)))))
  nil)

(defn ^:private get-user [access-token]
  (as-> (goget "https://www.googleapis.com/plus/v1/people/me"
               {:access-token access-token}                 ; TODO: HACK
               {:as :json}) it
        (:body it)
        {:id    (:id it)
         :name  (:displayName it)
         :email (get-in it [:emails 0 :value])}))

(defrecord Service [slug display-name client-id client-secret]
  services/Service
  (get-auth-uris [_] auth)
  (get-user-account [_ access-token] (get-user access-token)))

(defrecord Account
  [id user service access-token refresh-token sync-cursor]

  accounts/Account
  (init [account] account)
  (sync [account] (synchronize! account)))
