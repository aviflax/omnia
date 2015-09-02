(ns omnia-poc.google-drive
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [omnia-poc.db :as db])
  (:import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
           com.google.api.client.auth.oauth2.Credential
           com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
           com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
           com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow$Builder
           com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
           com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
           com.google.api.client.http.HttpTransport
           com.google.api.client.json.jackson2.JacksonFactory
           com.google.api.client.json.JsonFactory
           com.google.api.client.util.store.FileDataStoreFactory
           com.google.api.services.drive.Drive$Builder
           com.google.api.services.drive.DriveScopes
           java.util.ArrayList
           (java.io ByteArrayOutputStream)
           (com.google.api.client.auth.oauth2 BearerToken)
           (com.google.api.client.googleapis.json GoogleJsonResponseException)
           (com.google.api.client.googleapis.auth.oauth2 GoogleRefreshTokenRequest)))

(def json-factory (JacksonFactory/getDefaultInstance))

(def http-transport (GoogleNetHttpTransport/newTrustedTransport))

(def secret-json "{\"installed\":{\"client_id\":\"759316558410-elh22itait533b8d1sahuq39b4g96und.apps.googleusercontent.com\",\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\",\"token_uri\":\"https://accounts.google.com/o/oauth2/token\",\"auth_provider_x509_cert_url\":\"https://www.googleapis.com/oauth2/v1/certs\",\"client_secret\":\"***REMOVED***\",\"redirect_uris\":[\"urn:ietf:wg:oauth:2.0:oob\",\"http://localhost\"]}}")

(def client-secrets (GoogleClientSecrets/load json-factory (io/reader (.getBytes secret-json))))

(def scopes (ArrayList. [DriveScopes/DRIVE]))

(defn flow [] (-> (GoogleAuthorizationCodeFlow$Builder. http-transport json-factory client-secrets scopes)
                  (.setAccessType "offline")
                  (.build)))

(defn get-auth-url [flow]
  (.newAuthorizationUrl flow))

(defn get-creds-via-browser [flow]
  (-> (AuthorizationCodeInstalledApp. flow (LocalServerReceiver.))
      (.authorize "user")))

(defn get-access-token
  "get the token from the creds once it’s been authorized by the user in the browser"
  [creds]
  (.getAccessToken creds))

(defn get-refresh-token
  "get the token from the creds once it’s been authorized by the user in the browser"
  [creds]
  (.getRefreshToken creds))

(defn get-creds [token]
  (let [creds (Credential. (BearerToken/authorizationHeaderAccessMethod))]
    (.setAccessToken creds token)
    creds))

(defn get-service [{:keys [access-token name]}]
  (-> (Drive$Builder. http-transport json-factory (get-creds access-token))
      (.setApplicationName name)
      .build))

(defn get-file-content [id source]
  (let [stream (ByteArrayOutputStream.)]
    (as-> (get-service source) it
          (.files it)
          (.get it id)
          (.executeMediaAndDownloadTo it stream))
    (str stream)))

(defn get-file [id source]
  (as-> (get-service source) it
        (.files it)
        (.get it id)
        (.execute it)))

(defn refresh-token [service {:keys [client-id client-secret refresh-token]}]
  (let [access-token (as-> (GoogleRefreshTokenRequest. http-transport json-factory
                                                       refresh-token client-id client-secret) it
                           (do (println it) it)
                           (.execute it)
                           (.getAccessToken it))]
    (db/update-source-access-token "Google Drive" access-token)
    (.setAccessToken service access-token)))

(defn exec-with-refresh [req service]
  (try
    (.execute req)
    (catch GoogleJsonResponseException e
      (if (and (.getDetails e)
               (= (.getCode (.getDetails e)) 401))
          (do
            (refresh-token service req)
            (.execute req))
          (throw e)))))

(defn get-files [source]
  (let [service (get-service source)]
  (as-> (.files service) it
        (.list it)
        (.setMaxResults it (int 10))
        (.setOrderBy it "createdDate desc")
        (.setQ it "mimeType = 'text/plain'")
        (exec-with-refresh it service)
        (.getItems it)
        (map #(hash-map :name (.getTitle %)
                        :text (get-file-content (.getId %) source)
                        :mime-type (.getMimeType %))
             it))))
