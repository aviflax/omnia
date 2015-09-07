(ns omnia-poc.dropbox
  (:require [omnia-poc.db :as db]
            [clojure.string :refer [lower-case]])
  (:import [com.dropbox.core DbxAppInfo DbxRequestConfig DbxWebAuthNoRedirect DbxClient]
           java.util.Locale
           java.io.ByteArrayOutputStream))

(defn get-req-config []
  (DbxRequestConfig. "Omnia" (str (Locale/getDefault))))

(defn get-auth [{:keys [key secret]}]
  (let [app-info (DbxAppInfo. key secret)]
    (DbxWebAuthNoRedirect. (get-req-config) app-info)))

(defn get-auth-init-url [auth] (.start auth))

(defn get-token [auth code]
  (.accessToken (.finish auth code)))

; TODO: should this be reused?
(defn get-client [{:keys [access-token]}]
  (DbxClient. (get-req-config) access-token))

(defn get-file-content [path client]
  (let [stream (ByteArrayOutputStream.)]
    (.getFile client path nil stream)
    (str stream)))

(defn dropbox-file->omnia-file
  [client source file]
  (hash-map :name (.name file)
            :text (get-file-content (.path file) client)
            :omnia-source-id (lower-case (.path file))      ; lower-case to work around a possible bug in clucy
            :omnia-source (lower-case (:name source))))     ; lower-case to work around a possible bug in clucy

(defn get-files [path source]
  (let [client (get-client source)]
    (as-> client it
          (.getMetadataWithChildren it path)
          (.children it)
          (filter #(.isFile %) it)
          (map (partial dropbox-file->omnia-file client source)
               it))))
