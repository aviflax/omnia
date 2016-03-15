(ns omnia.index
  (:refer-clojure :exclude [replace])
  (:require [clojure.string :refer [replace]]
            [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.index :as esi]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest.response :as esrsp]
            [clojurewerkz.elastisch.query :as q]))

(defn ^:private connect [] (esr/connect "http://127.0.0.1:9200"))

(defn ^:private create-index []
  (esi/create
    (connect)
    "omnia"
    :mappings {"document" {:dynamic    false                ; services include all sorts of crazy things
                           :properties {:text             {:type   "string"
                                                           :store  "no" ; why store the text twice (as a field and in the _source)
                                                           :fields {:english {:type     "string"
                                                                              :analyzer "english"}}}
                                        :omnia-id         {:type  "string"
                                                           :store "yes"
                                                           :index "not_analyzed"}
                                        :omnia-account-id {:type  "string"
                                                           :store "yes"
                                                           :index "not_analyzed"}}}}))

(defn ^:private delete-index [confirmation]
  (if (= confirmation "yes, really")
      (let [response (esi/delete (connect) "omnia")]
        (when-not (esrsp/ok? response)
          (throw (.Exception (:status response)))))
      (throw (.Exception "confirmation not acceptable"))))

(defn ^:private multi-match-query
  "Multi-Match Query. This isn’t included in Elastisch for some reason.

  For more information, please refer to https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-multi-match-query.html"
  [fields query & args]
  {:multi_match {:type   :most_fields
                 :query  query
                 :fields fields}})

(defn search
  ([q] (search q 1 10))
  ([q page-num per-page]
   (let [size per-page
         from (- (* page-num per-page)
                 per-page)
         result (esd/search (connect)
                            "omnia"
                            "document"
                            :query (multi-match-query [:text :text.english] q)
                            :highlight {:fields    {:text* {:fragment_size 150, :number_of_fragments 1}}
                                        :pre_tags  ["<b>"]
                                        :post_tags ["</b>"]}
                            :_source {:exclude [:text]}
                            :size size
                            :from from)
         hits (as-> (get-in result [:hits :hits]) hits
                    (map #(assoc (:_source %)
                           :highlight (or (get-in % [:highlight :text.english 0])
                                          (get-in % [:highlight :text 0])))
                         hits))
         total (-> result :hits :total)]
     {:hits     hits
      :total    total
      :page-num page-num
      :per-page per-page})))

(defn delete [doc]
  "If the doc isn’t found, this is just a no-op"
  (println "Deleting" (:omnia-id doc) "from index, if present")
  (esd/delete (connect) "omnia" "document" (:omnia-id doc))
  ; return nil so other components don’t come to rely on the specific results of ElasticSearch
  nil)

(defn delete-all-docs-for-account [account]
  ;; TODO: This should really use esd/delete-by-query but I was having trouble with it. I suspect
  ;; this might be due to the version incompatibility between the current version of Elastisch and
  ;; ElasticSearch I’m using. TBD.
  (let [conn (connect)
        ids (->> (esd/search conn
                             "omnia"
                             "document"
                             :query (q/term :omnia-account-id (:id account))
                             :scroll "1m"
                             :_source false)
                 (esd/scroll-seq conn)
                 (map :_id))]
    (run! #(esd/delete conn "omnia" "document" %)
          ids))
  ; return nil so other components don’t come to rely on the specific results of ElasticSearch
  nil)

(defn add-or-update [doc]
  (println "Indexing" (dissoc doc :text))
  (esd/put (connect) "omnia" "document" (:omnia-id doc) doc)
  ; return nil so other components don’t come to rely on the specific results of ElasticSearch
  nil)
