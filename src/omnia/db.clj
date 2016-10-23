(ns omnia.db
  "
  Here are the required keys for each type:

  User: id, email, name
  Service: slug, display-name, client-id, client-secret.
  Account: id, user-id, service-slug, access-token, refresh-token, sync-cursor

  Service has these optional keys:
    for Dropbox: team-id, team-folder-id, team-folder-name, team-folder-path
    for Box: (coming soon)

  Accounts are conceptually a “bridge” that connects a User and a Service. In the DB, account
  docs store only the IDs of their Users and Services, for normalization. The API of this component,
  however, accepts and returns instances of omnia.accounts.core/Account (which are also expected
  to be associatives (maps)) which have :user and :service keys rather than just the IDs. In other
  words, this component is acting as a sort-of Data Access Object or maybe even a Object-Object
  Mapper (OOM). Is that good? Is it optimal? I’m not sure -- we’ll see!
  "
  (require [clojure.string :refer [blank? includes? lower-case]]
           [omnia.accounts.core :refer [map->Account]]
           [omnia.services.core :refer [map->Service]]
           [clojurewerkz.elastisch.rest :as esr]
           [clojurewerkz.elastisch.rest.document :as esd]
           [clojurewerkz.elastisch.rest.index :as esi]
           [clojurewerkz.elastisch.rest.response :refer [found? hits-from ok? total-hits]]
           [clojurewerkz.elastisch.query :as q])
  (:import [java.util UUID]))

(def ^:private es
  "TODO: move to config
   We’re using discrete indices for “database” type data and “documents” because they have
   significantly different indexing (mapping) requirements."
  {:uri "http://127.0.0.1:9200"})

;This is really index-name so maybe rename it accordingly.
;This is a var so it can have a different value for testing.
(def ^:private index)

(defn ^:private connect []
  (esr/connect (:uri es)
               {:connection-manager
                (clj-http.conn-mgr/make-reusable-conn-manager {:timeout 10})}))

(def ^:private conn (atom nil))

(def schemata
  "Required keys must be present, non-empty, non-blank, non-nil, and non-zero."
  ;; TODO: maybe use Prismatic Schema for this?
  {:service {:required [:slug :display-name :client-id :client-secret]}
   :user    {:required [:name :email]}})

(defn ^:private get-conn []
  (or @conn (throw (ex-info "Component is stopped" {}))))

(defn start [index-name]
  (def index index-name)
  (reset! conn (connect)))

(defn stop []
  ;; TODO: shutdown the connection manager
  (reset! conn nil)
  nil)

(def ^:const mappings
  {"service" {:properties {:slug {:type  "string"
                                  :index "not_analyzed"}}}
   "user"    {:properties {:email {:type  "string"
                                   :index "not_analyzed"}
                           :id    {:type  "string"
                                   :index "not_analyzed"}}}
   "account" {:properties {:id           {:type  "string"
                                          :index "not_analyzed"}
                           :user-id      {:type  "string"
                                          :index "not_analyzed"}
                           :service-slug {:type  "string"
                                          :index "not_analyzed"}}}})

(defn ^:private create-index
  "This is really here just for convenience."
  []
  (esi/create (get-conn) index :mappings mappings))

(defn ^:private new-id []
  (str (UUID/randomUUID)))

(defn account-id [service-slug service-account-id]
  "Accepts a service-slug and a the ID of a user account in its source service and returns an Omnia
  account ID for use with the DB."
  (str service-slug "/" service-account-id))

(defn ^:private create-entity [mapping-type entity id]
  ;; TODO: prevent creation of duplicate entities
  (let [response (esd/put (get-conn) index mapping-type id entity)]
    (when-not (ok? response)
      (throw (ex-info (-> response :status str) response)))))

(defn create-service
  "Creates the supplied service in the database, using its :slug as its id. Returns nil."
  [service]
  {:pre [(every? #(-> service % blank? not) [:slug :display-name :client-id :client-secret])
         (re-find #"^[a-z-]+$" (:slug service))]}
  (create-entity "service" service (:slug service)))

(defn create-account
  "Creates the supplied account in the database. It must have a value for :id.
   Returns the account as an Account."
  [account]
  {:pre [(contains? account :id)
         (not (blank? (:id account)))
         (not (blank? (get-in account [:user :id])))
         (not (blank? (get-in account [:service :slug])))
         (-> account :user map?)
         (-> account :service map?)
         (re-find #"^[a-zA-Z0-9-://]+$" (:id account))]}
  ;; TODO: prevent creation of duplicate accounts
  (let [doc (-> account
                (assoc :user-id (get-in account [:user :id])
                       :service-slug (get-in account [:service :slug]))
                (dissoc :user :service))]
    ; TODO: check result and throw if it failed
    (create-entity "account" doc (:id doc))
    (map->Account account)))

(defn ^:private get-source [mapping-type id]
  (-> (esd/get (get-conn) index mapping-type id
               ; this doesn’t seem to work - when an HTTP error occurs esd/get still just returns nil
               {:throw-exceptions true})
      :_source))

(defn ^:private account-map->Account
  "Accepts an account as a map containing :user-id and :service-slug, retrieves the referenced user
   and service, replaces the ID/slug keys with full values in :user and :service, and converts it
   into an Account."
  [account]
  (let [user (get-source "user" (:user-id account))
        service (get-source "service" (:service-slug account))]
    (when-not user
      (throw (ex-info (str "Could not find user with id " (:user-id account) " for account " (:id account)) account)))
    (when-not service
      (throw (ex-info (str "Could not find service with slug " (:service-slug account) " for account " (:id account)) account)))
    (-> (assoc account
          :user user
          :service service)
        (dissoc :user-id :service-slug)
        map->Account)))

(defn get-account
  "Retrieves an account by id. Returns nil if the account does not exist; throws an exception if
   something exceptional occurs (I think; TODO: confirm)."
  [id]
  (when-let [account (get-source "account" id)]
    (account-map->Account account)))

(defn get-accounts-for-user [user-id]
  (let [response (esd/search (get-conn) index "account"
                             :query (q/term :user-id user-id))]
    (map (comp account-map->Account :_source)
         (hits-from response))))

(defn ^:private update-failed?
  "Accepts a response from ElasticSearch as a map, returns a boolean."
  [response]
  (pos? (-> response :_shards :failed)))

(defn update-account
  "Given an id, key, and value, updates an Account. Returns nil on success, throws an exception on
   failure."
  [id key value]
  (let [response (esd/update-with-partial-doc (get-conn) index "account" id {key value})]
    (when (update-failed? response)
      (throw (ex-info (str response) response)))))

(defn delete-account [id]
  ; TODO: it might be better to just mark the account as logically deleted, rather than actually delete it.
  (let [response (esd/delete (get-conn) index "account" id)]
    (if (found? response)
        (do ; refresh the index so the change is immediately visible, then return nil.
            ; TODO: is this refresh a problematic side effect?
            (esi/refresh (get-conn))
            nil)
        (throw (ex-info (str "Could not find account with id " id " to delete it.") response)))))

(defn get-service [slug]
  ; Yes, we’re using service slugs as ElasticSearch IDs.
  (when-let [result (get-source "service" slug)]
    (map->Service result)))

(defn ^:private get-user-by-email [email]
  (let [response (esd/search (get-conn)
                             index
                             "user"
                             :query (q/term :email email))]
    (if (<= (total-hits response) 1)
        (-> response hits-from first :_source)
        (throw (ex-info (str "More than 1 user found with the email address " email) {})))))

(defn ^:private get-user-by-account-id [account-id]
  "We have an account ID but need the user associated with the account. We can look up the user ID
   in the account entity and then retrieve the user by that ID.
   TODO: look into doing this with a single request, some kind of a “join” or indirect query."
  (when-let [account (get-source "account" account-id)]
    (get-source "user" (:user-id account))))

(defn get-user
  "Attempts to retrieve a user via a supplied email address and/or account-id. If no matching user
   is found, returns nil.
   TODO: enforce that at least one criterion must be supplied
   TODO: actually implement the “and” part of the above description, or change the description.
   TODO: maybe simplify this; maybe remove the “and” and make the args simpler somehow.
   TODO: or maybe just 2 funcs: get-user-by-email and get-user-for-account"
  [{:keys [email account-id]
    :as   criteria}]
  (or (when email
        (get-user-by-email email))
      (when account-id
        (get-user-by-account-id account-id))))

(defn ^:private user-valid? [user]
  (and (every? #(-> user % blank? not) [:name :email])
       (includes? (:email user) "@")))

(defn create-user
  "Creates the supplied account in the database, assigning it a unique id in the key :id. Returns
   the “updated” user with the new :id."
  [user]
  {:pre [(user-valid? user)]}
  ;; TODO: prevent creation of duplicate users
  (let [id (new-id)
        user (assoc user :id id)]
    (create-entity "user" user id)
    user))

(defn ^:private get-one-account-for-service [service-slug]
  (as-> (esd/search (get-conn)
                    index
                    "account"
                    :query (q/term :service-slug service-slug)
                    :size 1) it
        (hits-from it)
        (first it)
        (when it
          (-> it :_source account-map->Account))))

(defn get-one-account-per-active-service []
  "For syncing. We need to sync accounts because we need active tokens to do so, and those are
   at the account level. But we only want to sync one account per service; so we don’t do redundant
   work"
  ;; TODO: this makes multiple successive requests to ES, so it’s quite inefficient. FIX THIS.
  ;; TODO: implement the “active” part -- right now this assumes that if a service exists in the DB, it’s active.
  (let [services (hits-from
                   (esd/search (get-conn)
                               index
                               "service"
                               :query (q/match-all)))]
    (->> (map (comp get-one-account-for-service :slug :_source) services)
         (remove nil?))))
