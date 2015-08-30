(ns omnia-poc.lucene
  (:require [clucy.core :as clucy]))

(def index (clucy/disk-index "data/lucene"))
