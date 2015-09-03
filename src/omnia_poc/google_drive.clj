(ns omnia-poc.google-drive
  (:require [omnia-poc.db :as db]
            [clj-http.client :as client]))

(defn get-access-token [{:keys [client-id client-secret refresh-token] :as source}]
  (let [url "https://www.googleapis.com/oauth2/v3/token"
        response (client/post url {:form-params {:client_id client-id
                                                 :client_secret client-secret
                                                 :grant_type "refresh_token"
                                                 :refresh_token refresh-token}
                                   :as :json})]
    ;(println response)
    (get-in response [:body :access_token])))

(defn goget [url {:keys [access-token refresh-token] :as source} & [opts]]
  ;; TODO: switch to try/catch and rethrow anything other than 401
  ;; TODO: figure out a way to update the source that is being used in outer contexts
  (let [response (client/get url (assoc opts :throw-exceptions false
                                             :oauth-token access-token))]
    (if (= (:status response) 401)
        (let [token (get-access-token source)]
          (println "got new access token" token " so updating source in DB")
          (db/update-source-access-token "Google Drive" token)
          (println "trying again with new access token" token)
          (goget url (assoc source :access-token token) opts))
        response)))

(defn get-file-list [{:keys [access-token] :as source}]
  (->> (goget "https://www.googleapis.com/drive/v2/files"
              source
              {:query-params {"maxResults" "10"
                              "spaces" "drive"
                              "q" "mimeType = 'text/plain'"}
               :as :json})
       :body
       :items
       (map (fn [file]
              (assoc file :name (:title file)
                          :mime-type (:mimeType file)
                          :source (:name source))))))

(defn add-text [source file]
  (let [response (goget (str "https://www.googleapis.com/drive/v2/files/" (:id file))
                        source
                        {:query-params {"alt" "media"}
                         :as :stream})
        text (slurp (:body response))]
    (assoc file :text text)))

(defn get-files [{:keys [access-token] :as source}]
  (as-> (get-file-list source) files
        (map (partial add-text source) files)))
