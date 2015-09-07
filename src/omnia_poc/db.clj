(ns omnia-poc.db
  (require [datomic.api :as d]))

(def uri "datomic:free://localhost:4334/omnia")

(defn connect [] (d/connect uri))

(def source-schema [{:db/id (d/tempid :db.part/db)
                     :db/ident :source/name
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one
                     :db/unique :db.unique/identity
                     :db/doc "Name of the Source, e.g. Dropbox"
                     :db.install/_attribute :db.part/db}
                    {:db/id (d/tempid :db.part/db)
                     :db/ident :source/client-id
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one
                     :db/doc "OAuth 2.0 Client ID for this installation of Omnia"
                     :db.install/_attribute :db.part/db}
                    {:db/id (d/tempid :db.part/db)
                     :db/ident :source/client-secret
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one
                     :db/doc "OAuth 2.0 Client Secret for this installation of Omnia"
                     :db.install/_attribute :db.part/db}
                    {:db/id (d/tempid :db.part/db)
                     :db/ident :source/access-token
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one
                     :db/doc "OAuth 2.0 Access Token for this Source — TODO: move this to a source-account entity"
                     :db.install/_attribute :db.part/db}
                    {:db/id (d/tempid :db.part/db)
                     :db/ident :source/refresh-token
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one
                     :db/doc "OAuth 2.0 Refresh Token for this Source — TODO: move this to a source-account entity"
                     :db.install/_attribute :db.part/db}
                    ])

(defn create-db []
  (d/create-database uri)
  (d/transact (connect) source-schema))

(defn create-source [name id secret token]
  (d/transact (connect) [{:db/id (d/tempid :db.part/user)
                          :source/name name
                          :source/client-id id
                          :source/client-secret secret
                          :source/token token}]))

(defn remove-namespace-from-map-keys [m]
  (apply hash-map (interleave (map (comp keyword name)
                                   (keys m))
                              (vals m))))

(defn get-source [name]
  (-> (d/pull (d/db (connect)) '[*] [:source/name name])
      remove-namespace-from-map-keys))

(defn update-source [source-name key value]
  (d/transact (connect) [{:db/id [:source/name source-name]
                          (keyword "source" (name key)) value}]))
