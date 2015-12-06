(ns omnia.core)

(defrecord User [id name])

(defrecord Service [id slug display-name client-id client-secret])

(defrecord Account [id user-id service-id access-token refresh-token sync-cursor])

(defrecord Document [id name path account-id])
