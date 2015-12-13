(ns omnia.db
  (require [datomic.api :as d]
           [omnia.core :refer [map->Account map->Service map->User]]))

(def ^:private uri "datomic:free://localhost:4334/omnia")   ;; TODO: move to config

(defn ^:private connect [] (d/connect uri))

(def user-schema
  [{:db/id                 (d/tempid :db.part/db)
    :db/ident              :user/email
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/unique             :db.unique/identity
    :db/doc                "The email address of a User. Long-term I’d rather not rely on email addresses but for now it’s fine."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :user/name
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "The name of the user, i.e. Ada Lovelace."
    :db.install/_attribute :db.part/db}
   ])

(def service-schema
  [{:db/id                 (d/tempid :db.part/db)
    :db/ident              :service/slug
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/unique             :db.unique/identity
    :db/doc                "Slug for a Service, e.g. dropbox or google-drive"
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :service/display-name
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/unique             :db.unique/identity
    :db/doc                "Display name of the Service, e.g. "
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :service/client-id
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "OAuth 2.0 Client ID for this Service for this installation of Omnia"
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :service/client-secret
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "OAuth 2.0 Client Secret for this Service for this installation of Omnia"
    :db.install/_attribute :db.part/db}
   ])

(def account-schema
  "An Account is a linked Account for a specific User for a given Service."
  [{:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/id
    :db/valueType          :db.type/uuid
    :db/cardinality        :db.cardinality/one
    :db/unique             :db.unique/identity
    :db/doc                "The ID of an Account."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/user
    :db/valueType          :db.type/ref
    :db/cardinality        :db.cardinality/one
    :db/doc                "The User that “owns” this account."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/service
    :db/valueType          :db.type/ref
    :db/cardinality        :db.cardinality/one
    :db/doc                "The service this is an account to/for/of e.g. Dropbox"
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/access-token
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "OAuth 2.0 Access Token for this Account for this User."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/refresh-token
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "OAuth 2.0 Refresh Token for this Account for this User."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/sync-cursor
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "Sync cursor value for those systems that use them."
    :db.install/_attribute :db.part/db}
   ])

; just for reference really (at least for now)
;(defn create-db []
;  (d/create-database uri)
;  (d/transact (connect)
;              (concat user-schema service-schema account-schema)))

; TBD:
; Does it make sense to isolate Datomic from the rest of the system?
;   Am I adding complexity (transformation) and dulling the advantages of Datomic by doing so?

(defn ^:private remove-namespace-from-map-keys [m]
  (apply hash-map (interleave (map (comp keyword name)
                                   (keys m))
                              (vals m))))

(defn ^:private entity->map [entity] (-> (d/touch entity)
                                         remove-namespace-from-map-keys))

(defn ^:private entity-id->map [db entity-id] (->> (d/entity db entity-id)
                                                   d/touch
                                                   remove-namespace-from-map-keys))

(defn ^:private get-entity [k v f]
  (let [e (d/pull (d/db (connect)) '[*] [k v])]
    (when-not (nil? (:db/id e))
      (-> e remove-namespace-from-map-keys f))))

(defn create-account [{:keys [user-email service-slug access-token refresh-token]}]
  (let [tempid (d/tempid :db.part/user)
        proto-account (as-> {} it
                            (assoc it
                              :db/id tempid
                              :account/id (d/squuid)
                              :account/user [:user/email user-email]
                              :account/service [:service/slug service-slug]
                              :account/access-token access-token)
                            (if refresh-token
                                (assoc it :account/refresh-token refresh-token)
                                it))
        tx-result @(d/transact (connect) [proto-account])
        db-after (:db-after tx-result)
        entity-id (d/resolve-tempid db-after (:tempids tx-result) tempid)
        entity (d/entity db-after entity-id)]
    (as-> (entity->map entity) it
          (assoc it :user (-> it :user entity->map map->User)
                    :service (-> it :service entity->map map->Service))
          (map->Account it))))

(defn get-account [id]
  (get-entity :account/id id map->Account))

(defn get-accounts [user-email]
  (let [db (d/db (connect))
        results (d/q '[:find ?account ?service
                       :in $ ?user-email
                       :where [?user :user/email ?user-email]
                       [?account :account/user ?user]
                       [?service :service/name]
                       [?account :account/service ?service]]
                     db user-email)]
    (map (fn [result]
           (-> (entity-id->map db (first result))
               (assoc :service (entity-id->map db (second result)))
               (dissoc :user)))
         results)))

(defn update-account [account key value]
  (let [attr (keyword "account" (name key))]
    @(d/transact (connect)
                 (if (nil? value)
                     [[:db/retract [:account/id (:id account)] attr (key account)]]
                     [{:db/id [:account/id (:id account)]
                       attr   value}]))))

(defn delete-account [account]
  @(d/transact (connect)
               [[:db.fn/retractEntity [:account/id (:id account)]]]))

(defn get-service [slug] (get-entity :service/slug slug map->Service))
