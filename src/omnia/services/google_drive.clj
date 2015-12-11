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
            [clojure.string :refer [blank? lower-case]]
            [clj-http.client :as client]))

(defn ^:private get-access-token
  [{:keys [refresh-token], {:keys [client-id client-secret]} :service}]
  (let [url "https://www.googleapis.com/oauth2/v3/token"
        response (client/post url {:form-params {:client_id     client-id
                                                 :client_secret client-secret
                                                 :grant_type    "refresh_token"
                                                 :refresh_token refresh-token}
                                   :as          :json})]
    (get-in response [:body :access_token])))

(defn ^:private goget
  [url {:keys [access-token] :as account} & [opts]]
  ;; TODO: switch to try/catch and rethrow anything other than 401
  ;; TODO: figure out a way to update the account that is being used in outer contexts
  (let [response (client/get url (assoc opts :throw-exceptions false
                                             :oauth-token access-token))]
    (if (= (:status response) 401)
        (let [token (get-access-token account)]
          (println "got new access token" token " so updating account in DB")
          (db/update-account account :access-token token)
          (println "trying again with new access token" token)
          (goget url (assoc account :access-token token) opts))
        response)))

(defn file->doc
  "Convert a Google Drive file to an Omnia document."
  [account file]
  (assoc file :name (:title file)
              :path nil                                     ; TODO: add path, if not toooo much of a hassle
              :mime-type (:mimeType file)
              :omnia-id (lower-case (:id file))             ; lower-case to work around a bug in clucy
              :omnia-account-id (:id account)
              :omnia-service-name (-> account :service :name))) ; TODO: probably doesn’t make sense to store this here; I can get it by reference via the account ID

(defn ^:private add-text
  "If the file’s mime type is text/plain, retrieves the text and adds it to the file map in :text.
  Otherwise returns the file map as-is. TODO: move the filtering elsewhere."
  [account file]
  (if (not= (:mimeType file) "text/plain")
      file
      (let [response (goget (str "https://www.googleapis.com/drive/v2/files/" (:id file))
                            account
                            {:query-params {"alt" "media"}
                             :as           :stream})
            text (slurp (:body response))]
        (assoc file :text text))))

(defn ^:private get-changes [account cursor]
  (goget "https://www.googleapis.com/drive/v2/changes"
         account
         {:as           :json
          :query-params (if (blank? cursor)
                            {}
                            {"pageToken" (-> cursor bigint int)})}))

(defn ^:private process-change-item! [account item]
  (if (:deleted item)
      (index/delete {:omnia-account-id (:id account)
                     :omnia-id         (lower-case (:fileId item))})
      (let [file (:file item)]
        (println (:title file))
        (->> (file->doc account file)
             (add-text account)
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
          (db/update-account account :sync-cursor (str next-cursor))
          (Thread/sleep 10)
          (recur next-cursor))
        (db/update-account account :sync-cursor (-> changes :largestChangeId bigint int inc str)))))
  nil)
