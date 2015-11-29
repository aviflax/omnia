(ns omnia.core)

(defrecord User [id name])

(defrecord Service [name client-id client-secret])

(defrecord Account [id user-id service-name access-token refresh-token sync-cursor])

(defrecord Document [id name path account-id])
