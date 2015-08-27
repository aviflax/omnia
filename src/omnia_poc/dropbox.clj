(ns omnia-poc.dropbox
  (:import [com.dropbox.core DbxAppInfo DbxRequestConfig DbxWebAuthNoRedirect DbxClient]
           java.util.Locale
           java.io.ByteArrayOutputStream))

(def dropbox-info {:key "ouymy7c0bwlntkv"
                   :secret "***REMOVED***"
                   :token "VH5oXhs6bCAAAAAAAACRKvZOPBzUBlKnWqUFOPR52LCTANZSoM-FFgl_DaqrmDUU"})

(defn get-req-config []
  (DbxRequestConfig. "Omnia" (str (Locale/getDefault))))

(defn get-auth []
  (let [app-info (DbxAppInfo. (:key dropbox-info) (:secret dropbox-info))]
    (DbxWebAuthNoRedirect. (get-req-config) app-info)))

(defn get-auth-init-url [auth] (.start auth))

(defn get-token [auth code]
  (.accessToken (.finish auth code)))

; TODO: should this be reused?
(defn get-client []
  (DbxClient. (get-req-config) (:token dropbox-info)))

(defn get-file-content [path]
  (let [client (get-client)
        stream (ByteArrayOutputStream.)]
    (.getFile client path nil stream)
    (str stream)))

(defn get-files [path]
  (as-> (get-client) it
        (.getMetadataWithChildren it path)
        (.children it)
        (filter #(.isFile %) it)
        (map #(hash-map :name (.name %)
                        :text (get-file-content (.path %)))
             it)))
