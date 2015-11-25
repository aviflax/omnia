(ns omnia.core)

(defrecord User [id name])

(defrecord Account-Type [name client-id client-secret])

(defrecord Account [id user-id type-name access-token refresh-token sync-cursor])

(defrecord Document [id name path account-id])
