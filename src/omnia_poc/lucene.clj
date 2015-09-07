(ns omnia-poc.lucene
  (:require [clucy.core :as clucy]))

(def index (clucy/disk-index "data/lucene"))

(defn delete-file [file]
  "If the file isn’t found, this is just a no-op"
  (clucy/delete index (select-keys file [:omnia-source :omnia-source-id])))

(defn fix-meta [file]
  (with-meta file {:omnia-source-id {:analyzed false :norms false}
                   :omnia-source {:analyzed false :norms false}}))

(defn add-file [file]
  "Be careful not to accidentally add duplicate entries to the index with this."
  (clucy/add index (fix-meta file)))

(defn add-or-update-file [file]
  "If the file is already in the index, it gets deleted then added. Otherwise just added.
   Yes, this is how you do updates in Lucene."
  (delete-file file)
  (add-file file))
