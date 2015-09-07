(ns omnia-poc.lucene
  (:require [clucy.core :as clucy]))

(def index (clucy/disk-index "data/lucene"))

(defn delete-file [file]
  (clucy/delete index (select-keys file [:omnia-source :omnia-source-id])))

(defn fix-meta [file]
  (with-meta file {:omnia-source-id {:analyzed false :norms false}
                   :omnia-source {:analyzed false :norms false}}))

(defn add-file [file]
  (clucy/add index (fix-meta file)))
