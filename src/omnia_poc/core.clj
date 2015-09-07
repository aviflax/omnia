(ns omnia-poc.core
  (:require [omnia-poc [db :as db]
                       [lucene :as lucene]
                       [dropbox :as dropbox]
                       [google-drive :as google-drive]]
            [clucy.core :as clucy]))

(defn index-google-drive-files []
  (doseq [file (google-drive/get-files (db/get-source "Google Drive"))]
    (lucene/add-or-update-file file)))

(defn search [q]
  (clucy/search lucene/index q 10))

(defn -main
  [& args]
  (println "Hello, World!"))
