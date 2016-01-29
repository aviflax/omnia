(ns omnia.accounts.core
  (:refer-clojure :exclude [sync]))

(defprotocol Account
  (init [account] "Initialize an Account quickly and synchronously.")
  (sync [account] "Retrieve list of changes from the service and update the index.")
  ;(connect [account])
  ;(get-doc-changes [account])
  ;(disconnect [account])
  )

(defn map->Account [account-map]
  ; Something less dynamic (e.g. a direct call to dropbox/map->Account etc using multimethods)
  ; would probably be simpler, but it would involve cycles.
  ; So this is my semi-crazy approach to avoiding cyclical
  ; dependencies at compile time. Probably not a great idea but working for now.
  (let [ns-sym (symbol (str "omnia.services." (-> account-map :service :slug)))
        _ (require ns-sym)
        factory @(ns-resolve ns-sym 'map->Account)]
    (factory account-map)))
