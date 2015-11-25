;; TODO: rename this to “index”
(ns omnia-poc.lucene
  (:require [clucy.core :as clucy]))

(def index (clucy/disk-index "data/lucene"))

(defn ^:private trunc
  [s n]
  (if (nil? s)
    ""
    (subs s 0 (min (count s) n))))

(defn search [q]
  (map
    #(as-> % result
           ;; TODO: get a useful/relevant snippet from Lucene
           (assoc result :snippet (trunc (:text result) 100))
           (dissoc result :text))
    (clucy/search index q 10)))

(defn delete-file [file]
  "If the file isn’t found, this is just a no-op"
  (println "Deleting" (:name file) "from index, if present")
  (clucy/delete index (select-keys file [:omnia-file-id :omnia-account-id])))

(defn delete-all-docs-for-account [account]
  (clucy/delete index {:omnia-account-id (:id account)}))

(defn fix-meta [file]
  (with-meta file {:omnia-account-id        {:analyzed false :norms false}
                   :omnia-account-type-name {:analyzed false :norms false}}))

(defn add-file [file]
  "Be careful not to accidentally add duplicate entries to the index with this."
  (println "Indexing" (:name file) "from" (:omnia-account-type-name file))
  (clucy/add index (fix-meta file)))

(defn add-or-update-file [file]
  "If the file is already in the index, it gets deleted then added. Otherwise just added.
   Yes, this is how you do updates in Lucene."
  (delete-file file)
  (add-file file))
