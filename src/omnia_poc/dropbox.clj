(ns omnia-poc.dropbox
  (:require [omnia-poc [db :as db]
             [lucene :as lucene]]
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

(defn dropbox-file->omnia-file-with-text
  [client source file]
  (let [f (hash-map :name (.name file)
                    ; lower-case to work around a possible bug in clucy
                    :omnia-source-id (lower-case (.path file))
                    ; lower-case to work around a possible bug in clucy
                    :omnia-source (lower-case (:name source)))]
    (if (.endsWith (.path file) ".txt")                     ; TODO: make this much more sophisticated!
        (assoc f :text (get-file-content (.path file) client))
        f)))

(defn process-delta-entry! [client source entry]
  (if (nil? (.metadata entry))
      (lucene/delete-file {:omnia-source (lower-case (:name source))
                           :omnia-source-id (lower-case (.lcPath entry))})
      (when (.isFile (.metadata entry))                     ; TODO: handle directories?
        (println (-> (.metadata entry) .path))
        (lucene/add-or-update-file (dropbox-file->omnia-file-with-text client source (.metadata entry)))
        (Thread/sleep 10))))

(defn synchronize [{:keys [sync-cursor] :as source}]
  (let [client (get-client source)]
    (loop [cursor sync-cursor]
      (let [delta (.getDelta client cursor)]
        (run! (partial process-delta-entry! client source)
              (.entries delta))

        ; update source cursor in DB
        (db/update-source "Dropbox" :sync-cursor (.cursor delta))

        ; get more
        (when (.hasMore delta)
          (recur (.cursor delta)))))))
