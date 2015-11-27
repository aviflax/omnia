(ns omnia.index
  (:require [clucy.core :as clucy]))

(def ^:private index (clucy/disk-index "data/lucene"))

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
  (with-meta file {:omnia-file-id           {:analyzed false :norms false} ; it’s important not to analyze this because it sometimes contain chars that Lucene by default will split up, e.g. `/`
                   :omnia-account-id        {:analyzed false :norms false} ; not absolutely sure we need this to not be analyzed but probably harmless for now
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
