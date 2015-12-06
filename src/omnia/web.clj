(ns omnia.web
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [compojure.core :refer [defroutes GET]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.util.response :refer [redirect]]
            [ring.util.codec :refer [url-encode]]
            [hiccup.page :refer [html5]]
            [hiccup.form :as f]
            [omnia.core :refer [map->Account]]
            [omnia.index :as index]
            [omnia.db :as db]
            [omnia.services.core :refer [synch]]
            [clojure.string :refer [blank? capitalize join split trim]]
            [clj-http.client :as client]))

(defn ^:private search-field [query]
  [:input {:type      "search"
           :name      "q"
           :id        "q"
           :value     query
           :required  ""                                    ; save a few bytes!
           :autofocus ""}])

(defn ^:private header
  ([] (header ""))
  ([title]
   [:header [:h1 [:a {:href "/"} "Omnia"]
             (when (not (blank? title))
               (str " » " title))]]))

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
  (if (contains? doc :alternateLink)
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
          [:title "Accounts « Omnia"]]
         [:body
          (header "Accounts")
          [:ul
           (for [account (db/get-accounts "avi@aviflax.com")]
             [:li (-> account :service :display-name) " (avi@aviflax.com)"])]
          [:a {:href "/accounts/connect"} "Connect a New Account"]]))

(defn ^:private accounts-connect-get []
  (html5 [:head
          [:title "Connect a New Account « Omnia"]]
         [:body
          (header "<a href=\"/accounts\">Accounts</a> » Connect a New Account")
          [:section
           [:h1 "Which type of Account would you like to connect?"]
           [:p [:a {:href "/accounts/connect/dropbox/start"} "Dropbox"]]
           [:p [:a {:href "/accounts/connect/gdrive/start"} "Google Drive"]]]]))

(defn ^:private accounts-connect-service-start-get [service-slug]
  (let [ns-sym (symbol (str "omnia.services." service-slug))
        _ (require ns-sym)
        service-auth @(ns-resolve ns-sym 'auth)
        oauth2-start-uri (-> service-auth :oauth2 :start-uri)
        service (db/get-service service-slug)
        client-id (:client-id service)
        callback-uri (str "http://localhost:3000/accounts/connect/" service-slug "/finish")
        uri (str oauth2-start-uri
                 "?client_id=" client-id
                 "&response_type=code"
                 "&redirect_uri=" (url-encode callback-uri)
                 "&state=TODO")]
    (redirect uri 307)))

(def ^:private bad-request {:status  400
                            :headers {"Content-Type" "text/plain"}
                            :body    "Bad request"})

(defn ^:private get-access-token [service-slug auth-code]
  ;; TODO: generalize this
  (let [service (db/get-service service-slug)
        url "https://api.dropboxapi.com/1/oauth2/token"]
    (client/post url {:form-params {:client_id     (:client-id service)
                                    :client_secret (:client-secret service)
                                    :code          auth-code
                                    :grant_type    "authorization_code"
                                    :redirect_uri "http://localhost:3000/accounts/connect/dropbox/finish"}
                      :as          :json
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
    (let [access-token-response (get-access-token service-slug auth-code)
          access-token (-> access-token-response :body :access_token)]
      (if (blank? access-token)
          bad-request
          (let [service (db/get-service service-slug)
                ;; TODO: check the account userid and don’t create a duplicate new account if it’s already connected
                ;; TODO: should probably include the account userid (e.g. the Dropbox userid) in the account
                ;; TODO: stop using email to associate accounts with users
                account (map->Account {:user-email   "avi@aviflax.com"
                                       :service-id   (:id service)
                                       :access-token access-token})]
            ;; TODO: get user account email address
            (db/create-account account)
            (future (synch account))
            (redirect (str "/accounts/connect/" service-slug "/done") 307))))))

(defn ^:private accounts-connect-service-done-get [_]
  (html5 [:head
          [:title "New Account Connected « Omnia"]]
         [:body
          (header "Accounts » New Account Connected")
          [:section
           ;; TODO: Generalize
           [:h1 "Your new Dropbox account has been connected!"]
           [:p "We’ve started indexing your new account in the background. We’ll send you an email when we’re done!"]

           [:p [:a {:href "/accounts"} "Back to Accounts"]]]]))

(defroutes routes
           (GET "/" [] (handle-index))
           (GET "/search" [q] (handle-search q))
           (GET "/accounts" [] (accounts-get))
           (GET "/accounts/connect" [] (accounts-connect-get))
           (GET "/accounts/connect/:service-slug/start" [service-slug] (accounts-connect-service-start-get service-slug))
           (GET "/accounts/connect/:service-slug/finish" [service-slug code state] (accounts-connect-service-finish-get service-slug code state))
           (GET "/accounts/connect/:service-slug/done" [service-slug] (accounts-connect-service-done-get service-slug)))

(def app
  (-> routes
      wrap-params
      wrap-stacktrace))

(defn start []
  (run-jetty app {:port 3000 :join? false}))
