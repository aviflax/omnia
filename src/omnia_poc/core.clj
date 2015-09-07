(ns omnia-poc.core
  (:require [omnia-poc.db :as db]
            [omnia-poc.dropbox :as dropbox]
            [omnia-poc.google-drive :as google-drive]
            [omnia-poc.lucene :as lucene]
            [clucy.core :as clucy]))

(defn fix-meta [file]
  (with-meta file {:omnia-source-id {:analyzed false :norms false}
                   :omnia-source {:analyzed false :norms false}}))

(defn index-dropbox-files []
  (doseq [file (dropbox/get-files "/" (db/get-source "Dropbox"))]
    (clucy/delete lucene/index (select-keys file [:omnia-source :omnia-source-id]))
    (clucy/add lucene/index (fix-meta file))))

(defn index-google-drive-files []
  (doseq [file (google-drive/get-files (db/get-source "Google Drive"))]
    (clucy/delete lucene/index (select-keys file [:omnia-source :omnia-source-id]))
    (clucy/add lucene/index (fix-meta file))))

(defn search [q]
  (clucy/search lucene/index q 10))

(defn -main
  [& args]
  (println "Hello, World!"))
