(ns omnia.dropbox
  (:require [omnia [db :as db]
             [index :as lucene]]
            [clojure.string :refer [lower-case split]])
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

(defn should-get-full-text? [file]
  (or (.endsWith (.path file) ".txt")                 ; TODO: make this much more sophisticated!
      (.endsWith (.path file) ".md")))

(defn should-index? [metadata-entry]
  (and (.isFile metadata-entry)
       (not (some #(.startsWith % ".")
                  (split (.path metadata-entry) #"/")))
       (should-get-full-text? metadata-entry))) ;; TEMP TEMP Just to speed up full-account indexing

(defn dropbox-file->omnia-file-with-text
  [client account file]
  (let [f (hash-map :name (.name file)
                    :path (.path file)
                    ;; TODO: include account ID in omnia-file-id so as to ensure uniqueness and avoid conflicts
                    :omnia-file-id (lower-case (.path file)) ; lower-case to work around a possible bug in clucy
                    :omnia-account-id (:id account)
                    :omnia-account-type-name (-> account :type :name))]
    (if (should-get-full-text? file)
        (assoc f :text (get-file-content (.path file) client))
        f)))

(defn process-delta-entry! [client account entry]
  (if-let [md (.metadata entry)]
    (if (should-index? md)
        (do
          (println "indexing" (.path md))
          (-> (dropbox-file->omnia-file-with-text client account md)
              lucene/add-or-update-file))
        (println "skipping" (.path md)))
    (lucene/delete-file {;;:omnia-account-id (:id account)
                         :omnia-file-id (lower-case (.lcPath entry))})))

(defn synchronize! [{:keys [sync-cursor] :as account}]
  (let [client (get-client account)]
    (loop [cursor sync-cursor]
      (let [;delta (.getDeltaWithPathPrefix client cursor "/Articles")
            delta (.getDelta client cursor)
            ]
        (run! (partial process-delta-entry! client account)
              (.entries delta))

        ; update account cursor in DB
        (db/update-account account :sync-cursor (.cursor delta))

        (Thread/sleep 5)

        ; get more
        (when (.hasMore delta)
          (recur (.cursor delta)))))))
