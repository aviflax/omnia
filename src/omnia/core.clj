(ns omnia.core)

(defrecord User [email name])

(defrecord Service [slug display-name client-id client-secret])

(defrecord Account [id user service access-token refresh-token sync-cursor])

;(defrecord Document [id name path account-id])
