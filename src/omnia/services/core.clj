(ns omnia.services.core)

(defprotocol Service
  "A low-level object that is used for interacting with a Service outside of the context of a
  specific user account. Most of the time an omnia.accounts.Account is more useful, but there are
  a few cases, such as during login/account-connection, when it’s necessary to communicate with a
  service prior to retrieving/instantiating an omnia.accounts.Account."

  (get-auth-uris [service]
    "Get the URIs needed for authentication")

  (get-user-account [service access-token]
    "Returns a map with the keys :id, :name, :email, all with string values"))

(defn map->Service [service-map]
  ; Something less dynamic (e.g. a direct call to dropbox/map->Account etc using multimethods)
  ; would probably be simpler, but it would involve cycles.
  ; So this is my semi-crazy approach to avoiding cyclical
  ; dependencies at compile time. Probably not a great idea but working for now.
  (let [ns-sym (symbol (str "omnia.services." (:slug service-map)))
        _ (require ns-sym)
        factory @(ns-resolve ns-sym 'map->Service)]
    (factory service-map)))