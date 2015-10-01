(ns omnia-poc.google-drive
  "If might seem odd that this ns uses GDrive’s REST API directly via HTTP
   rather than employing GDrive’s Java SDK that should hypothetically provide
   a more convenient way to integrate with GDrive, similar to the Dropbox case.
   The reason is that GDrive’s Java SDK is an unusable piece of crap. It’s classic
   awful Java: millions of classes and factories and builders and awful docs on
   how to actually *use* all that crap."
  (:require [omnia-poc.db :as db]
            [omnia-poc.lucene :as lucene]
            [clojure.string :refer [blank? lower-case]]
            [clj-http.client :as client]))

(defn get-access-token
  ^:private
  [{:keys [client-id client-secret refresh-token] :as source}]
  (let [url "https://www.googleapis.com/oauth2/v3/token"
        response (client/post url {:form-params {:client_id client-id
                                                 :client_secret client-secret
                                                 :grant_type "refresh_token"
                                                 :refresh_token refresh-token}
                                   :as :json})]
    (get-in response [:body :access_token])))

(defn goget
  ^:private
  [url {:keys [access-token refresh-token] :as source} & [opts]]
  ;; TODO: switch to try/catch and rethrow anything other than 401
  ;; TODO: figure out a way to update the source that is being used in outer contexts
  (let [response (client/get url (assoc opts :throw-exceptions false
                                             :oauth-token access-token))]
    (if (= (:status response) 401)
        (let [token (get-access-token source)]
          (println "got new access token" token " so updating source in DB")
          (db/update-source "Google Drive" :access-token token)
          (println "trying again with new access token" token)
          (goget url (assoc source :access-token token) opts))
        response)))

(defn gdrive-file->omnia-file [source file]
  (assoc file :name (:title file)
              :path nil ; TODO: add path, if not toooo much of a hassle
              :mime-type (:mimeType file)
              :omnia-source-id (lower-case (:id file))      ; lower-case to work around a bug in clucy
              :omnia-source (lower-case (:name source))))   ; lower-case to work around a bug in clucy

(defn add-text
  "If the file’s mime type is text/plain, retrieves the text and adds it to the file map in :text.
  Otherwise returns the file map as-is. TODO: move the filtering elsewhere."
  [source file]
  (if (not= (:mimeType file) "text/plain")
      file
      (let [response (goget (str "https://www.googleapis.com/drive/v2/files/" (:id file))
                            source
                            {:query-params {"alt" "media"}
                             :as :stream})
            text (slurp (:body response))]
        (assoc file :text text))))

(defn ^:private get-changes [source cursor]
  (goget "https://www.googleapis.com/drive/v2/changes"
         source
         {:as :json
          :query-params (if (blank? cursor)
                            {}
                            {"pageToken" (-> cursor bigint int inc)})}))

(defn process-change-item! [source item]
  (if (:deleted item)
      (lucene/delete-file {:omnia-source (lower-case (:name source))
                           :omnia-source-id (lower-case (:fileId item))})
      (let [file (:file item)]
        (println (:title file))
        (->> (gdrive-file->omnia-file source file)
             (add-text source)
             lucene/add-or-update-file)
        (Thread/sleep 100))))

(defn synchronize! [source]
  (loop [cursor (:sync-cursor source)]
    (let [response (get-changes source cursor)
          changes (:body response)]
      (when (not= (:status response) 200)
        (throw (Exception. "ruh roh")))

      (run! (partial process-change-item! source)
            (:items changes))

      ; update source cursor in DB
      (db/update-source "Google Drive" :sync-cursor (str (:largestChangeId changes)))

      ; get more
      (when (not (blank? (:nextLink changes)))
        (recur (:largestChangeId changes))))))
