(ns omnia-poc.core
  (:require [omnia-poc.db :as db]
            [omnia-poc.dropbox :as dropbox]
            [omnia-poc.google-drive :as google-drive]
            [omnia-poc.lucene :as lucene]
            [clucy.core :as clucy]))

(defn index-dropbox-files []
  (doseq [file (dropbox/get-files "/" (db/get-source "Dropbox"))]
    (clucy/add lucene/index file)))

(defn index-google-drive-files []
  (doseq [file (google-drive/get-files (db/get-source "Google Drive"))]
    (clucy/add lucene/index file)))

(defn search [q]
  (clucy/search lucene/index q 10))

(defn -main
  [& args]
  (println "Hello, World!"))
