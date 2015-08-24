(ns omnia-poc.core
  (:import [com.dropbox.core DbxAppInfo DbxRequestConfig DbxWebAuthNoRedirect DbxClient]
           java.util.Locale))

(def dropbox-info {:key "ouymy7c0bwlntkv"
                   :secret "***REMOVED***"
                   :token "VH5oXhs6bCAAAAAAAACRKvZOPBzUBlKnWqUFOPR52LCTANZSoM-FFgl_DaqrmDUU"})

(defn get-req-config []
  (DbxRequestConfig. "Omnia" (str (Locale/getDefault))))

(defn get-dropbox-auth []
  (let [app-info (DbxAppInfo. (:key dropbox-info) (:secret dropbox-info))]
    (DbxWebAuthNoRedirect. (get-req-config) app-info)))

(defn get-dropbox-client []
  (DbxClient. (get-req-config) (:token dropbox-info)))

(defn get-root-files []
  (as-> (get-dropbox-client) it
        (.getMetadataWithChildren it"/")
        (.children it)
        (filter #(.isFile %) it)
        (doseq [file it]
          (println (.name file))
          (-> (get-dropbox-client)
            (.getFile (.path file) nil System/out)))))

(defn -main
  [& args]
  (println "Hello, World!"))
