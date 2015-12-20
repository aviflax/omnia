(ns omnia.index
  "TODO: maybe rework the semantics of the add/update API… I’ve had issues wherein a doc wasn’t being deleted even
  though it *was* in the index. To be clear, that was my fault, due to a bug I introduced. Still, it’d be nice if that
  case would result in throwing an exception rather than adding a duplicate entry to the index."
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
           ;; TODO: as a performance optimization, try not retrieving the full text from the index
           (assoc result :snippet (trunc (:text result) 100))
           (dissoc result :text))
    (clucy/search index q 10)))

(defn delete [doc]
  "If the doc isn’t found, this is just a no-op"
  (println "Deleting" (:name doc) "from index, if present")
  (clucy/delete index (select-keys doc [:omnia-id :omnia-account-id])))

(defn delete-all-docs-for-account [account]
  (clucy/delete index {:omnia-account-id (:id account)}))

(defn fix-meta [doc]
  (with-meta doc {:omnia-id           {:analyzed false :norms false} ; it’s important not to analyze this because it sometimes contain chars that Lucene by default will split up, e.g. `/`
                  :omnia-account-id   {:analyzed false :norms false} ; not absolutely sure we need this to not be analyzed but probably harmless for now
                  :omnia-service-name {:analyzed false :norms false}}))

(defn add [doc]
  "Be careful not to accidentally add duplicate entries to the index with this."
  (println "Indexing" (:name doc) "from" (:omnia-service-name doc))
  (clucy/add index (fix-meta doc)))

(defn add-or-update [doc]
  "First the doc is deleted — which is a noop if the doc’s not in the index. Then it’s added.
   Yes, this is how you do updates in Lucene."
  (delete doc)
  (add doc))
