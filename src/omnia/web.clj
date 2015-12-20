(ns omnia.web
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [compojure.core :refer [defroutes GET DELETE]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.util.response :refer [redirect]]
            [ring.util.codec :refer [url-encode]]
            [hiccup.page :refer [html5]]
            [hiccup.form :as f]
            [omnia
             [accounts :as accounts]
             [core :refer [map->Account]]
             [index :as index]
             [db :as db]]
            [omnia.services.core :refer [get-auth synch]]
            [clojure.string :refer [blank? capitalize join split trim]]
            [clj-http.client :as client])
  (:import [java.util UUID]))

(defn ^:private search-field [query]
  [:input {:type      "search"
           :name      "q"
           :id        "q"
           :value     query
           :required  ""                                    ; save a few bytes!
           :autofocus ""}])

(defn ^:private header
  ([] (header ""))
  ([& title-segments]
   [:header [:h1 [:a {:href "/"} "Omnia"]
             (when (seq title-segments)
                   (str " » "
                        (apply str title-segments)))]]))

(def ^:private footer [:footer [:a {:href "/accounts"} "Manage Accounts"]])

(defn ^:private handle-index []
  (html5 [:head
          [:title "Omnia"]]
         [:body
          (header)
          (f/form-to [:get "/search"]
                     (search-field "")
                     (f/submit-button "Search"))
          footer]))

(defn ^:private link [doc]
  ;; TODO: generalize
  (if (contains? doc :alternateLink)                        ; google drive uses alternateLink
      (:alternateLink doc)
      (let [path-segments (split (:omnia-id doc) #"/")
            dir-path (join "/" (butlast path-segments))]
        (str "https://www.dropbox.com/home" dir-path "?preview=" (last path-segments)))))

(defn ^:private capitalize-each-word [s]
  (as-> ((fnil split "") s #" ") it                         ;; TEMP TEMP UGLY HACK TEMP TEMP
        (map capitalize it)
        (join " " it)))

(defn ^:private handle-search [query]
  (if (blank? (trim query))
      (redirect "/" 307)
      (let [results (doall (index/search query))]
        (html5 [:head
                [:title "Search for “" query "” « Omnia"]]
               [:body
                (header)
                (f/form-to [:get "/search"]
                           (search-field query)
                           (f/submit-button "Search"))
                [:section#results
                 (for [result results]
                   [:section.result
                    [:h1
                     [:a {:href (link result)}
                      (:name result)]]
                    [:label.path (:path result)]
                    [:p.snippet (:snippet result) "…"]
                    [:label.source
                     "("
                     (-> result :omnia-service-name capitalize-each-word)
                     ")"]
                    [:hr]])]
                footer]))))

(defn ^:private accounts-get []
  (html5 [:head
          [:title "Accounts « Omnia"]
          [:style "form { display: inline; margin-left: 1em;}"]]
         [:body
          (header "Accounts")
          [:ul
           (for [account (db/get-accounts "avi@aviflax.com")
                 :let [service (:service account)]]
             [:li (:display-name service)
              (f/form-to [:delete (str "/accounts/" (:id account))]
                         (f/submit-button "Disconnect"))])]
          [:a {:href "/accounts/connect"} "Connect a New Account"]]))

(defn ^:private accounts-connect-get []
  (html5 [:head
          [:title "Connect a New Account « Omnia"]]
         [:body
          (header "<a href=\"/accounts\">Accounts</a> » Connect a New Account")
          [:section
           [:h1 "Which type of Account would you like to connect?"]
           [:p [:a {:href "/accounts/connect/dropbox/start"} "Dropbox"]]
           [:p [:a {:href "/accounts/connect/google-drive/start"} "Google Drive"]]]]))

; This is harder than it looks.
;(defn ^:private build-uri [base & query-fragments]
;  (str base
;       (when-not (.contains base "?")
;           "?")
;       (join "" query-fragments)))

(defn ^:private build-service-connect-start-uri [service]
  (let [oauth (-> service get-auth :oauth2)
        client-id (:client-id service)
        callback-uri (str "http://localhost:3000/accounts/connect/" (:slug service) "/finish")]
    (str (:start-uri oauth)
         "client_id=" client-id
         "&response_type=code"
         "&redirect_uri=" (url-encode callback-uri)
         "&state=TODO")))

(defn ^:private accounts-connect-service-start-get [service-slug]
  (if-let [service (db/get-service service-slug)]
    (let [service-name (:display-name service)
          next-uri (build-service-connect-start-uri service)]
      (html5 [:head
              [:title "Connect a New " service-name " Account « Omnia"]]
             [:body
              (header "<a href=\"/accounts\">Accounts</a>")
              [:h1 "So you want to connect your " service-name " account…"]
              [:h2 "Here’s the deal:"]
              [:ul (case (:slug service)
                     "dropbox"
                     (seq [[:li "If you choose to continue, we’ll direct you to " service-name ", who will ask you whether
                            you’d like to give us permission to access your documents."]

                           [:li "Dropbox doesn’t offer a way for us to request read-only access, so if you want us to index
                            your documents, we’ll need full access to your Dropbox account — but we promise that we will
                            only ever <i>read</i> your Dropbox data, never write to it."]

                           [:li "And we’ll only index the documents you’ve shared with your entire team and/or company."]])

                     "google-drive"
                     [:li "TODO: add explanatory text here!"]

                     "Something went wrong here!")]
              [:p]
              [:p "Would you like to continue?"]
              [:ul
               [:li [:a {:href next-uri} "Continue to " service-name]]
               [:li [:a {:href "/accounts"} "Never mind"]]]]))
    {:status 404
     :body   "Not found, oh no!"}))

(def ^:private bad-request {:status  400
                            :headers {"Content-Type" "text/plain"}
                            :body    "Bad request"})

(defn ^:private get-access-token [service-slug auth-code]
  (let [service (db/get-service service-slug)
        oauth (-> service get-auth :oauth2)
        url (:token-uri oauth)]
    (client/post url {:form-params           {:client_id     (:client-id service)
                                              :client_secret (:client-secret service)
                                              :code          auth-code
                                              :grant_type    "authorization_code"
                                              :redirect_uri  (str "http://localhost:3000/accounts/connect/" service-slug "/finish")}
                      :as                    :json
                      :throw-entire-message? true})))

(defn ^:private accounts-connect-service-finish-get [service-slug auth-code state]
  (cond
    (not= state "TODO")
    {:status  500                                           ; not actually an internal server error but I don’t want to reveal to an attacker what the problem is
     :headers {"Content-Type" "text/plain"}
     :body    "Internal Server Error"}

    (blank? auth-code)
    bad-request

    :default
    (let [token-response (-> (get-access-token service-slug auth-code) :body)]
      (if (blank? (:access_token token-response))
          bad-request
          (let [service (db/get-service service-slug)
                ;; TODO: check the account userid and don’t create a duplicate new account if it’s already connected
                ;; TODO: should probably include the account userid (e.g. the Dropbox userid) in the account
                ;; TODO: stop using email to associate accounts with users
                proto-account (map->Account {:user-email    "avi@aviflax.com"
                                             :service-slug  service-slug
                                             :access-token  (:access_token token-response)
                                             :refresh-token (:refresh_token token-response)})
                account (db/create-account proto-account)]
            (future
              (try (synch account)
                   (catch Exception e (println e))))
            (redirect (str "/accounts/connect/" service-slug "/done") 307))))))

(defn ^:private accounts-connect-service-done-get [service-slug]
  (let [service (db/get-service service-slug)]
    (html5 [:head
            [:title "New Account Connected « Omnia"]]
           [:body
            (header "Accounts » New Account Connected")
            [:section
             [:h1 "Your new " (:display-name service) " account has been connected!"]
             [:p "We’ve started indexing your new account. We’ll send you an email when we’re done!"]
             [:p [:a {:href "/accounts"} "Back to Accounts"]]]])))

(defn ^:private account-delete [id]
  ;; TODO: make this async. Sure, I could just wrap it with `future`, but then the user
  ;; would navigate back to /accounts and would still see the account they asked to disconnect.
  ;; I’ll need some way to mark an account as “disconnect in progress”
  (-> (UUID/fromString id)
      db/get-account
      accounts/disconnect)
  (redirect "/accounts" 303))

(defroutes routes
           (GET "/" [] (handle-index))
           (GET "/search" [q] (handle-search q))
           (GET "/accounts" [] (accounts-get))
           (GET "/accounts/connect" [] (accounts-connect-get))
           (GET "/accounts/connect/:service-slug/start" [service-slug] (accounts-connect-service-start-get service-slug))
           (GET "/accounts/connect/:service-slug/finish" [service-slug code state] (accounts-connect-service-finish-get service-slug code state))
           (GET "/accounts/connect/:service-slug/done" [service-slug] (accounts-connect-service-done-get service-slug))
           (DELETE "/accounts/:id" [id] (account-delete id)))

(def app
  (-> routes
      wrap-params
      wrap-stacktrace))

(defn start []
  (run-jetty app {:port 3000 :join? false}))
