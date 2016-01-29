(ns omnia.db
  (require [datomic.api :as d]
           [omnia.core :refer [map->User]]
           [omnia.accounts.core :refer [map->Account]]
           [omnia.services.core :refer [map->Service]]))

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
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/unique             :db.unique/identity
    :db/doc                "The ID of an Account. A composite value: {service-slug}/{id of account in service}."
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

(def dropbox-attributes
  "These attributes are used by both Accounts and Services. An individual Account will have a Dropbox team folder, but
   the service will as well. This association lives at the Service level so we don’t sync the same folder multiple
   times redundantly when multiple users on the same team connect their Dropbox accounts.

   So wait, I have to wonder —
   do I really need this to live at the Account level at all? For what purpose? I supposed hypothetically there could
   be multiple team folders or something like that, or multiple folders I want to sync, some of which show up in one
   person’s account but not another’s — but YAGNI, you know? For now I’m only gonna support indexing a single folder
   for Dropbox for a given instance of Omnia, and that folder will be the team folder found when the first user
   connects their Dropbox account. BTW another implication of this is that when the last person disconnects the last
   Dropbox account in the system, we need to then dissasociate the team folder from the Service, at that point — right?

   OK so as of 3 January, here’s where I stand on this: while it’s true that I ultimately only want to maintain the
   association with and state of the team folder at the service level (although perhaps I could even make the team
   folder an “entity” and associate services and/or accounts with it) that’s really a performance optimization. I mean,
   maintaining the data at the account level and syncing the same folder multiple times for a given installation won’t
   lead to incorrect data — just redundant work. I.E. it’s inefficient. But I can live with runtime inefficiency
   *for now* — right now my focus needs to be on UX and functionality, not efficiency. And sticking with all syncing
   being at the account level — for now — lets me keep things simple and consistent across services, for now.

   ----

   On the 4th or the 5th I somehow lost the above paragraph in a stash, and so attempted to rewrite it like so:

   > OK a few days later and I’ve decided to hold off on associating these attributes with the Dropbox Service, at this
   > point, for now. Having them associated with the Accounts is sub-optimal, I think, but at the moment it’s more or less
   > working and while it’s not very efficient — this will lead to indexing the same files repeatedly and redundantly —
   > fixing this is more or less a performance optimization, and that’s not where I need to be focusing my efforts right
   > now. I need to focus on functionality and UX right now. And I need velocity. Keeping syncing at the Account level for
   > now means less work for me and also more consistency within the system as that’s how Google Drive works as well.

   I’m retaining both paragraphs for now because one of them might express an idea that the other does not express, or
   does not express well, and I just don’t have the energy to merge them right now."

  [{:db/id                 (d/tempid :db.part/db)
    :db/ident              :dropbox/team-id
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "Dropbox Team ID. Used to ensure that only the members of this team can log in to this instance of Omnia."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :dropbox/team-folder-id
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "Dropbox Team Folder ID."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :dropbox/team-folder-name
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "Dropbox Team Folder name."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :dropbox/team-folder-path
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "Dropbox Team Folder path."
    :db.install/_attribute :db.part/db}])

; just for reference really (at least for now)
;(defn create-db []
;  (d/create-database uri)
;  (d/transact (connect)
;              (concat user-schema service-schema account-schema)))

; TBD:
; Does it make sense to isolate Datomic from the rest of the system?
;   Am I adding complexity (transformation) and dulling the advantages of Datomic by doing so?

(defn ^:private remove-namespace-from-map-keys [m]
  (as-> (dissoc m :db/id) m                                 ; we don’t care about :db/id in userland; it’s internal to Datomic. And we don’t want it to overwrite any other attributes with the same name (less namespace)
        (apply hash-map (interleave (map (comp keyword name)
                                         (keys m))
                                    (vals m)))))

(defn ^:private entity->map
  "We need this mainly because Datomic entities don’t support dissoc"
  [e]
  (as-> (d/touch e) it
        (apply hash-map (interleave (keys it) (vals it)))
        (remove-namespace-from-map-keys it)))

(defn ^:private entity-id->map [db entity-id] (-> (d/entity db entity-id)
                                                  entity->map))

(defn ^:private pull-entity [k v]
  (let [e (d/pull (d/db (connect)) '[*] [k v])]
    (when-not (nil? (:db/id e))
      (-> e remove-namespace-from-map-keys))))

(defn ^:private account-entity->map [e]
  (-> (entity->map e)
      (assoc
        :user (entity-id->map (d/entity-db e) (get-in e [:account/user :db/id]))
        :service (entity-id->map (d/entity-db e) (get-in e [:account/service :db/id])))))

(defn account-id [service-slug service-account-id]
  "Accepts a service-slug and a the ID of a user account in its source service and returns an Omnia
  account ID for use with DB lookups."
  (str service-slug "/" service-account-id))

(defn create-account [{:keys [id user-email service-slug access-token refresh-token
                              team-folder-id team-folder-name team-folder-path]}]
  (let [tempid (d/tempid :db.part/user)
        proto-account (as-> {} it
                            (assoc it
                              :db/id tempid
                              :account/id id
                              :account/user [:user/email user-email]
                              :account/service [:service/slug service-slug]
                              :account/access-token access-token)
                            (if refresh-token
                                (assoc it :account/refresh-token refresh-token)
                                it)
                            (if (and (= service-slug "dropbox")
                                     team-folder-id)
                                (assoc it :dropbox/team-folder-id team-folder-id
                                          :dropbox/team-folder-name team-folder-name
                                          :dropbox/team-folder-path team-folder-path)
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
  (when-let [e (d/entity (d/db (connect)) [:account/id id])]
    (-> e account-entity->map map->Account)))

(defn get-accounts [user-email]
  (let [db (d/db (connect))
        results (d/q '[:find ?account ?service
                       :in $ ?user-email
                       :where [?user :user/email ?user-email]
                       [?account :account/user ?user]
                       [?service :service/name]
                       [?account :account/service ?service]]
                     db user-email)]
    (map
      ;; TODO: use account-entity->map instead of this anonyomous function
      (fn [result]
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
  ; TODO: check that something was actually retracted, and if not either return an error value or raise an exception
  @(d/transact (connect)
               [[:db.fn/retractEntity [:account/id (:id account)]]]))

(defn get-service [slug]
  (when-let [e (pull-entity :service/slug slug)]
    (map->Service e)))


