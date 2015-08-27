(ns omnia-poc.google-drive
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint])
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
           (java.io ByteArrayOutputStream)))

(def app-info {:name          "Omnia"
               :client-id     "759316558410-elh22itait533b8d1sahuq39b4g96und.apps.googleusercontent.com\nHere is your client secret\n***REMOVED***"
               :client-secret "***REMOVED***"
               :token "ya29.2wHWKnZYou_BLljo8bLfnHIMUMd7X-T2_lsAiNnIOpeZTkmJ2557soDVztNnSq64crix"})

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

(defn get-creds [flow]
  (-> (AuthorizationCodeInstalledApp. flow (LocalServerReceiver.))
      (.authorize "user")))

; Don’t actually need this but I just like to be able to get it
(defn get-token [creds]
  (.getAccessToken creds))

(defn get-service [creds]
  (-> (Drive$Builder. http-transport json-factory creds)
      (.setApplicationName (:name app-info))
      .build))

(defn get-file-content [id creds]
  (let [stream (ByteArrayOutputStream.)]
    (as-> (get-service creds) it
          (.files it)
          (.get it id)
          (.executeMediaAndDownloadTo it stream))
    (str stream)))

(defn get-file [id creds]
  (as-> (get-service creds) it
        (.files it)
        (.get it id)
        (.execute it)))

(defn get-files [creds]
  (as-> (get-service creds) it
        (.files it)
        (.list it)
                  (.setMaxResults it (int 10))
                  (.setOrderBy it "createdDate desc")
                  (.setQ it "mimeType = 'text/plain'")
        (.execute it)
        (.getItems it)
        (map #(hash-map :name (.getTitle %)
                        :text (get-file-content (.getId %) creds)
                        :mime-type (.getMimeType %))
             it)))
