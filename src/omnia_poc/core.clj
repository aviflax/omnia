(ns omnia-poc.core
  (:require [omnia-poc.lucene :as lucene]
            [clucy.core :as clucy]))

(defn search [q]
  (clucy/search lucene/index q 10))

(defn -main
  [& args]
  (println "Hello, World!"))
