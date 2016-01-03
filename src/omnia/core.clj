(ns omnia.core)

(defrecord User [email name])

;(defrecord Document [id name path account-id])

;; TODO: Since I’ll have myriad Services and Accounts, and I need polymorphism, I’m thinking maybe Service and Account
;; should be protocols (or sets of protocols) rather than concrete records... I mean, the types that implement the
;; protocols could be concrete records, but would be more specific, like DropboxAccount or GoogleDriveAccount. I’m not
;; sure these “top-level” generic records make sense anymore.

;; TODO: maybe a Service should be a Source, which is a little more general. Think of, for example, an individual
;; document uploaded/added by a user, or of sources which aren’t exactly services, like a local network drive, or...
;; what? Maybe YAGNI? Maybe the cost of going so abstract would outweigh the benefit...?
(defrecord Service [slug display-name client-id client-secret])

(defrecord Account [id user service access-token refresh-token sync-cursor
                    ; TODO: since the following are specific to Dropbox, should they have a prefix, or be contained
                    ; inside a composite value, something like `service-specific-things` (ugh)?
                    team-folder-id
                    team-folder-name
                    team-folder-path])


