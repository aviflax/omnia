(ns omnia-poc.core
  (:require [omnia-poc [db :as db]
                       [lucene :as lucene]
                       [dropbox :as dropbox]
                       [google-drive :as google-drive]]
            [clucy.core :as clucy]))

(defn index-dropbox-files []
  (doseq [file (dropbox/get-files "/" (db/get-source "Dropbox"))]
    (lucene/delete-file file)
    (lucene/add-file file)))

(defn index-google-drive-files []
  (doseq [file (google-drive/get-files (db/get-source "Google Drive"))]
    (lucene/delete-file file)
    (lucene/add-file file)))

(defn search [q]
  (clucy/search lucene/index q 10))

(defn -main
  [& args]
  (println "Hello, World!"))
