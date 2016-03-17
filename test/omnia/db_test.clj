(ns omnia.db-test
  (:use midje.sweet
        omnia.db)
  (:require [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.index :as esi])
  (:import [omnia.accounts.core Account]
           [java.util UUID]
           [clojure.lang ExceptionInfo]))

(def conn
  "for “direct” connections to ElasticSearch, in order to validate side effects"
  (esr/connect "http://127.0.0.1:9200"))

(def index-name
  "the plain name “index” would conflict with a var in the ns under test"
  "omnia-db-test")

(namespace-state-changes (before :facts (do (esi/delete conn index-name)
                                            (esi/create conn index-name {:mappings mappings
                                                                         ; TODO: setting the refresh_interval isn’t helping so far
                                                                         :settings {:index {:refresh_interval "1ms"}}})
                                            (start index-name)))
                         (after :facts (do (stop)
                                           (esi/delete conn index-name))))

(defn ^:private _get-source
  "This is defined here so we don’t rely on the ns under test to verify itself. I.E. I want
   these tests to *independently* verify the ns under test.
   The prefix is so it doesn’t conflict with get_source in the ns under test."
  [mapping-type id]
  (-> (esd/get conn index-name mapping-type id)
      :_source))

(def valid-user
  {:name  "Avi"
   :email "avi@aviflax.com"})

(def valid-service
  ; must use a real slug of a real service because it must match an actual namespace
  {:slug          "dropbox"
   :display-name  "Dropbox"
   :client-id     "foo"
   :client-secret "bar"})

(def valid-account
  {:id      (account-id "dropbox" "34598345984375")
   :user    (assoc valid-user :id "fooo23424")
   :service valid-service})

(facts "about create-user"
       (facts "if required keys are missing from its input, it throws"
              (fact (create-user (dissoc valid-user :email)) => (throws AssertionError))
              (fact (create-user (dissoc valid-user :name)) => (throws AssertionError)))
       (facts "if required keys contain invalid values, it throws"
              (fact (create-user (assoc valid-user :email "")) => (throws AssertionError))
              (fact (create-user (assoc valid-user :name "")) => (throws AssertionError)))
       ; TODO / TBD if I really need this.
       ;(facts "if a user with the same email address already exists"
       ;       (fact "it returns nil"
       ;             (create-user valid-user)
       ;             (esi/refresh conn)
       ;             (create-user valid-user)
       ;             => nil)
       ;       (fact "it does not create a document"
       ;             (create-user valid-user)
       ;             (esi/refresh conn)
       ;             (create-user valid-user)
       ;             (esi/refresh conn)
       ;             ;TODO
       ;             => (= (esd/get conn index-name "user" (:id valid-user)))
       (facts "when it succeeds"
              (let [result (create-user valid-user)]
                (fact "the result is a map"
                      result => associative?)
                (fact "the result contains the same keys and values as the input"
                      (dissoc result :id) => valid-user)
                (fact "the result contains :id which contains a non-empty string"
                      result => (contains {:id #".+"}))
                (fact "the user is then retrievable by its id"
                      (create-user valid-user)              ; need to do this again because the index is deleted after each check
                      => (fn [result]
                           (= result
                              (-> (esd/get conn index-name "user" (:id result))
                                  :_source)))))))

(facts "about create-service"
       (tabular (fact "if required keys are missing from its input, it throws"
                      (create-service (dissoc valid-service ?k)) => (throws AssertionError))
                ?k :slug :display-name :client-id :client-secret)
       (tabular (fact "if required keys contain blank values, it throws"
                      (create-service (assoc valid-service ?k "")) => (throws AssertionError))
                ?k :slug :display-name :client-id :client-secret)
       (tabular (fact "if :slug contains an invalid value, it throws"
                      (create-service (assoc valid-service :slug ?v)) => (throws AssertionError))
                ?v "Vogons" "v ogons")
       (fact "it allows :slug to contain dash/hyphen chars"
             (create-service (assoc valid-service :slug "google-drive")) => nil)
       (facts "when it succeeds"
              (fact "the result is nil"
                    (create-service valid-service) => nil?)
              (fact "the service is then retrievable by its slug"
                    (create-service valid-service)
                    (_get-source "service" (:slug valid-service)) => (complement nil?))
              (fact "when retrieved, the service contains the same keys and values as the input"
                    (create-service valid-service)
                    (_get-source "service" (:slug valid-service)) => valid-service)))

(facts "about create-account"
       (tabular (fact "if required keys are missing from its input, it throws"
                      (create-account (dissoc valid-account ?k)) => (throws AssertionError))
                ?k :id :user :service)
       (tabular (fact "if required keys contain blank values, it throws"
                      (create-account (assoc valid-account ?k "")) => (throws AssertionError))
                ?k :id :user :service)
       (tabular (fact "if :id contains an invalid value, it throws"
                      (create-account (assoc valid-account :id ?v)) => (throws AssertionError))
                ?v "Vogons are fun" "v*ogons")
       (fact "if the user has no :id, it throws"
             (create-account (update-in valid-account [:user] dissoc :id)) => (throws AssertionError))
       (facts "when it succeeds"
              (fact "the result is an Account"
                    (create-account valid-account) => (partial instance? Account))
              (fact "the account is then retrievable by its id"
                    (create-account valid-account)
                    (_get-source "account" (:id valid-account)) => (complement nil?))
              (fact "when retrieved, the service contains the same keys and values as the input"
                    (create-account valid-account)
                    (_get-source "account" (:id valid-account))
                    => (-> valid-account
                           (assoc :service-slug (:slug valid-service))
                           (assoc :user-id (-> valid-account :user :id))
                           (dissoc :service :user)))))

(facts "about get-service"
       (fact "if a service with the specified slug does not exist, it returns nil"
             (get-service "fooooo") => nil)
       (fact "if a service with the specified slug exists, it returns that service"
             (create-service valid-service)
             (get-service (:slug valid-service)) => valid-service)
       (fact "if elasticsearch returns an error, it throws"
             ; Can’t test this right now because Elastisch swallows errors; it just returns nil.
             ; Gonna have to look into either changing Elastisch or working around it.
             (with-redefs [index-name "foo"]
               (get-service "foo") =future=> (throws Exception))))

(facts "about get-user"
       (fact "if only :email is provided and a user with the specified email address does not exist,
              it returns nil"
             (get-user {:email "foo@foo.com"}) => nil)
       (fact "if only :account-id is provided and a user with the specified account-id does not
              exist, it returns nil"
             (get-user {:account-id "123456"}) => nil)
       (fact "if both :email and :account-id are provided and a user with the specified criteria
              does not exist, it returns nil"
             (get-user {:email "foo@foo.com" :account-id "123456"}) => nil)
       (fact "if only :email is provided and a user with the specified email address exists, it
              returns that user"
             (create-user valid-user)
             (esi/refresh conn)
             (-> (get-user (select-keys valid-user [:email]))
                 (dissoc :id)) => valid-user)
       (fact "if only :account-id is provided and a user with the specified account-id exists, it
              returns that user"
             (create-user valid-user)
             ; TODO: create an account for this user
             (esi/refresh conn)
             (-> (get-user (select-keys valid-user [:email]))
                 (dissoc :id)) =future=> valid-user)
       (fact "if elasticsearch returns an error, it throws"
             ; Can’t test this right now because Elastisch swallows errors; it just returns nil.
             ; Gonna have to look into either changing Elastisch or working around it.
             (with-redefs [index-name "foo"]
               (get-user {:email "foo"}) =future=> (throws Exception))))

(facts "about get-account"
       (fact "if a account with the specified id does not exist, it returns nil"
             (get-account "fooooo") => nil)
       (facts "if a account with the specified id exists"
              (fact "it returns that account"
                    ; need to actually create a user in the DB so it can be retrieved along with the account
                    (let [user (create-user valid-user)
                          account (assoc valid-account :user user)]
                      (create-service valid-service)
                      (create-account account)
                      (esi/refresh conn)
                      (get-account (:id account)) => (contains account)))
              (fact "the result implements Account"
                    ; need to actually create a user in the DB so it can be retrieved along with the account
                    (let [user (create-user valid-user)
                          account (assoc valid-account :user user)]
                      (create-service valid-service)
                      (create-account account)
                      (esi/refresh conn)
                      (get-account (:id account)) => (partial instance? Account)))
              (fact "if the specified user does not exist, it throws"
                    (create-service valid-service)
                    (create-account valid-account)
                    (esi/refresh conn)
                    (get-account (:id valid-account)) => (throws Exception #"user"))
              (fact "if the specified service does not exist, it throws"
                    (let [user (create-user valid-user)
                          account (assoc valid-account :user user)]
                      (comment "skipping this:" (create-service valid-service))
                      (create-account account)
                      (esi/refresh conn)
                      (get-account (:id account)) => (throws Exception #"service"))))

       (fact "if elasticsearch returns an error, it throws"
             ; Can’t test this right now because Elastisch swallows errors; it just returns nil.
             ; Gonna have to look into either changing Elastisch or working around it.
             (with-redefs [index-name "foo"]
               (get-account "foo") =future=> (throws Exception))))

(facts "about get-one-account-per-active-service"
       (fact "when a user has an account for each of two services, both accounts are returned"
             (let [user (create-user valid-user)
                   dropbox-account (assoc valid-account :user user)
                   google-drive (assoc valid-service :slug "google-drive"
                                                     :display-name "Google Drive")
                   gdrive-account {:id      (account-id "google-drive" "954684958")
                                   :user    user
                                   :service google-drive}]
               (run! create-service [valid-service google-drive])
               (run! create-account [dropbox-account gdrive-account])
               (esi/refresh conn)
               (get-one-account-per-active-service)
               => (just #{(contains gdrive-account)
                          (contains dropbox-account)}))))

(facts "about update-account"
       (facts "when given the id of an existing account and an arbitrary kv-pair"
              (fact "it returns nil"
                    (create-account valid-account)
                    (update-account (:id valid-account) :foo :bar) => nil)
              (fact "the kv pair is subsequently included when retrieving the account"
                    (let [user (create-user valid-user)
                          account (assoc valid-account :user user)]
                      (create-service valid-service)
                      (create-account account)
                      (update-account (:id account) :foo "bar")
                      (esi/refresh conn)
                      (get-account (:id account)) => (contains (assoc account :foo "bar")))))
       (fact "if elasticsearch returns an error, it throws"
             (let [user (create-user valid-user)
                   account (assoc valid-account :user user)]
               (create-service valid-service)
               (create-account account)
               (stop)
               (start (str (UUID/randomUUID)))
               (update-account (:id account) :foo "bar") => (throws Exception))))

(facts "about delete-account"
       (facts "when it succeeds"
              (fact "the result is nil"
                    (create-account valid-account)
                    (esi/refresh conn)
                    (delete-account (:id valid-account)) => nil?)

              (fact "the account is no longer retrievable"
                    (create-account valid-account)
                    (esi/refresh conn)
                    (delete-account (:id valid-account))
                    (esi/refresh conn)
                    (get-account (:id valid-account)) => nil?))
       (fact "when the account doesn’t exist"
             (delete-account (:id valid-account)) => (throws Exception #"(?i)could not find account")))
