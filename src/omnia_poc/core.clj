(ns omnia-poc.core
  (:require [omnia-poc.dropbox :as dropbox]
            [omnia-poc.lucene :as lucene]
            [clucy.core :as clucy]))

(defn index-dropbox-files [path]
  (doseq [file (dropbox/get-files "/")]
    (clucy/add lucene/index file)))

(defn search [q]
  (clucy/search lucene/index q 10))

(defn -main
  [& args]
  (println "Hello, World!"))
