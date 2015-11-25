(ns omnia.google-drive
  "If might seem odd that this ns uses GDrive’s REST API directly via HTTP
   rather than employing GDrive’s Java SDK that should hypothetically provide
   a more convenient way to integrate with GDrive, similar to the Dropbox case.
   The reason is that GDrive’s Java SDK is an unusable piece of crap. It’s classic
   awful Java: millions of classes and factories and builders and awful docs on
   how to actually *use* all that crap."
  (:require [omnia
             [db :as db]
             [lucene :as lucene]]
            [clojure.string :refer [blank? lower-case]]
            [clj-http.client :as client]))

(defn get-access-token
  ^:private
  [{:keys [refresh-token], {:keys [client-id client-secret]} :type}]
  (let [url "https://www.googleapis.com/oauth2/v3/token"
        response (client/post url {:form-params {:client_id     client-id
                                                 :client_secret client-secret
                                                 :grant_type    "refresh_token"
                                                 :refresh_token refresh-token}
                                   :as          :json})]
    (get-in response [:body :access_token])))

(defn goget
  ^:private
  [url {:keys [access-token refresh-token] :as account} & [opts]]
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

(defn gdrive-file->omnia-file [account file]
  (assoc file :name (:title file)
              :path nil                                     ; TODO: add path, if not toooo much of a hassle
              :mime-type (:mimeType file)
              :omnia-file-id (lower-case (:id file))        ; lower-case to work around a bug in clucy
              :omnia-account-id (:id account)
              :omnia-account-type-name (-> account :type :name))) ; TODO: probably doesn’t make sense to store this here; I can get it by reference via the account ID

(defn add-text
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
                            {"pageToken" (-> cursor bigint int inc)})}))

(defn process-change-item! [account item]
  (if (:deleted item)
      (lucene/delete-file {:name             (:title item)
                           :omnia-account-id (:id account)
                           :omnia-file-id    (lower-case (:fileId item))})
      (let [file (:file item)]
        (println (:title file))
        (as-> (gdrive-file->omnia-file account file) document
              (add-text account document)
              (lucene/add-or-update-file document))
        (Thread/sleep 100))))

(defn synchronize! [account]
  (loop [cursor (:sync-cursor account)]
    (let [response (get-changes account cursor)
          changes (:body response)]
      (when (not= (:status response) 200)
        (throw (Exception. "ruh roh")))

      (run! (partial process-change-item! account)
            (:items changes))

      ; update account cursor in DB
      (db/update-account account :sync-cursor (str (:largestChangeId changes)))

      ; get more
      (when (not (blank? (:nextLink changes)))
        (recur (:largestChangeId changes))))))
