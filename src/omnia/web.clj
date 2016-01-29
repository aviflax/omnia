(ns omnia.web
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [compojure.core :refer [defroutes routes GET POST DELETE]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.util.response :refer [redirect]]
            [ring.util.codec :refer [url-encode]]
            [hiccup.page :refer [html5]]
            [hiccup.form :as f]
            [omnia
             [index :as index]
             [db :as db]]
            [omnia.accounts.core :refer [map->Account init] :as accounts]
            [omnia.accounts.util :as accounts.util]
            [omnia.services.core :refer [get-auth-uris get-user-account]]
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
  ([] (header nil))
  ([& title-segments]
   [:header [:h1 [:a {:href "/"} "Omnia"]
             (when (some some? title-segments)
                   (str " » "
                        (apply str title-segments)))]]))

(def ^:private footer
  [:footer
   {:style "margin-top: 10em;"}
   [:a {:href "/accounts"} "Manage Accounts"]
   (f/form-to [:post "/logout"]
              (f/submit-button "Log out"))])

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
                 (for [result results
                       :let [path-segments (split (:path result) #"/")
                             path (join "/" (butlast path-segments))]]
                   [:section.result
                    [:h1
                     [:a {:href (link result)}
                      (:name result)]]
                    [:label.source (-> result :omnia-service-name capitalize-each-word)]
                    (when-not (blank? path) ": ")
                    [:label.path path]
                    [:p.snippet (:snippet result) "…"]
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
           [:p [:a {:href "/accounts/connect/google-drive/start"} "Google Drive"]]
           [:p "Do you need to connect a service not shown here? Let us know! {LINK}"]]]))

; This is harder than it looks.
;(defn ^:private build-uri [base & query-fragments]
;  (str base
;       (when-not (.contains base "?")
;           "?")
;       (join "" query-fragments)))

(defn ^:private build-service-auth-start-uri [path-fragment service]
  (let [oauth (-> service get-auth-uris :oauth2)
        client-id (:client-id service)
        callback-uri (str "http://localhost:3000/" path-fragment "/" (:slug service) "/finish")]
    (str (:start-uri oauth)
         "&client_id=" client-id
         "&response_type=code"
         "&redirect_uri=" (url-encode callback-uri)
         "&state=TODO")))

(defn ^:private accounts-connect-service-start-get [service-slug]
  (if-let [service (db/get-service service-slug)]
    (let [service-name (:display-name service)
          next-uri (build-service-auth-start-uri "accounts/connect" service)]
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

                           [:li "And we’ll only index the documents in your “team” folder, which are already shared with
                                 your entire team/organization."]])

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

(defn ^:private get-access-token [path-fragment service-slug auth-code]
  (let [service (db/get-service service-slug)
        oauth (-> service get-auth-uris :oauth2)
        url (:token-uri oauth)]
    (client/post url {:form-params           {:client_id     (:client-id service)
                                              :client_secret (:client-secret service)
                                              :code          auth-code
                                              :grant_type    "authorization_code"
                                              :redirect_uri  (str "http://localhost:3000/" path-fragment "/" service-slug "/finish")}
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
    (let [token-response (-> (get-access-token "accounts/connect" service-slug auth-code) :body)]
      ;; TODO: after authorization confirm that the user actually connected a work account (Dropbox)
      (if (blank? (:access_token token-response))
          bad-request
          (let [service (db/get-service service-slug)
                ;; TODO: check the account userid and don’t create a duplicate new account if it’s already connected
                ;; TODO: should probably include the account userid (e.g. the Dropbox userid) in the account
                ;; TODO: stop using email to associate accounts with users
                account (-> {:user-email    "avi@aviflax.com"
                             :service-slug  service-slug
                             :access-token  (:access_token token-response)
                             :refresh-token (:refresh_token token-response)}
                            map->Account
                            init
                            db/create-account)]
            (future
              (try (accounts/sync account)
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
      accounts.util/disconnect)
  (redirect "/accounts" 303))

(defn ^:private login-get []
  (html5 [:head
          [:title "Log in « Omnia"]]
         [:body
          (header "Log in")
          [:section
           [:h1 "With which service would you like to log in?"]
           [:p [:a {:href "/login/with/dropbox/start"} "Dropbox"]]
           [:p [:a {:href "/login/with/google-drive/start"} "Google Drive"]]
           [:p "Is there some other service not shown here you’d like to use to log in? Let us know! {LINK}"]]]))

(defn ^:private login-start-get [service-slug]
  (if-let [service (db/get-service service-slug)]
    (let [service-name (:display-name service)
          next-uri (build-service-auth-start-uri "login/with" service)]
      (html5 [:head
              [:title "Log in with " service-name " « Omnia"]]
             [:body
              (header "Log in with " service-name)
              [:h1 "You want to log in! That’s fantastic!"]
              [:h2 "Here’s the deal:"]
              [:ul (case (:slug service)
                     "dropbox"
                     (seq [[:li "If you choose to continue, we’ll direct you to " service-name ", who will ask you whether
                                 you’d like to give us permission to access your documents."]

                           [:li "Dropbox doesn’t offer a way for us to request read-only access, so if you want us to index
                                 your documents, we’ll need full access to your Dropbox account — but we promise that we will
                                 only ever <i>read</i> your Dropbox data, never write to it."]

                           [:li "And we’ll only index the documents in your “team” folder, which are already shared with
                                 your entire team/organization."]])

                     "google-drive"
                     [:li "TODO: add explanatory text here!"]

                     "Something went wrong here!")]
              [:p]
              [:p "Would you like to continue?"]
              [:ul
               [:li [:a {:href next-uri} "Continue to " service-name]]
               [:li [:a {:href "/login"} "Never mind"]]]]))
    {:status 404
     :body   "Not found, oh no!"}))

(defn ^:private login-finish-get [service-slug auth-code state]
  (cond
    (not= state "TODO")
    {:status  500                                           ; not actually an internal server error but I don’t want to reveal to an attacker what the problem is
     :headers {"Content-Type" "text/plain"}
     :body    "Internal Server Error"}

    (blank? auth-code)
    bad-request

    :default
    (let [token-response (-> (get-access-token "login/with" service-slug auth-code) :body)
          access-token (:access_token token-response)]
      ;; TODO: after authorization confirm that the user actually connected a work account (Dropbox)
      (if (blank? access-token)
          bad-request
          (let [service (db/get-service service-slug)
                user-account (get-user-account service access-token)
                omnia-account-id (db/account-id service-slug (:id user-account))
                ;; TODO: check the account userid and don’t create a duplicate new account if it’s already connected
                ;; TODO: should probably include the account userid (e.g. the Dropbox userid) in the account
                ;; TODO: stop using email to associate accounts with users
                account (or (db/get-account omnia-account-id)
                            (-> {:id            omnia-account-id
                                 :user          (select-keys user-account [:email])
                                 :service       {:slug service-slug}
                                 :access-token  access-token
                                 :refresh-token (:refresh_token token-response)}
                                map->Account
                                init
                                db/create-account))]
            (future
              (try (accounts/sync account)
                   (catch Exception e (println e))))
            ;; TODO: if the account is new, redirect to a page that explains what’s happening.
            (-> (redirect "/" 307)
                (assoc-in [:session :user] "TODO")))))))

(defn handle-logout-post []
  (-> (redirect "/login" 307)
      (assoc :session nil)))

(defn wrap-restricted [handler]
  (fn [{session :session :as req}]
    (if (nil? (:user session))
        (redirect "/login" 307)
        (handler req))))

(def restricted-routes
  (wrap-restricted
    (routes
      (GET "/" [] (handle-index))
      (GET "/search" [q] (handle-search q))
      (GET "/accounts" [] (accounts-get))
      (GET "/accounts/connect" [] (accounts-connect-get))
      (GET "/accounts/connect/:service-slug/start" [service-slug] (accounts-connect-service-start-get service-slug))
      (GET "/accounts/connect/:service-slug/finish" [service-slug code state] (accounts-connect-service-finish-get service-slug code state))
      (GET "/accounts/connect/:service-slug/done" [service-slug] (accounts-connect-service-done-get service-slug))
      (DELETE "/accounts/:id" [id] (account-delete id)))))

(defroutes open-routes
           (GET "/login" [] (login-get))
           (GET "/login/with/:service-slug/start" [service-slug] (login-start-get service-slug))
           (GET "/login/with/:service-slug/finish" [service-slug code state] (login-finish-get service-slug code state))
           (POST "/logout" [] (handle-logout-post)))

(def app
  (-> (routes open-routes
              restricted-routes)
      (wrap-session {:store (cookie-store {:key "a 16-byte secret"})})
      wrap-params
      wrap-stacktrace))

(defn start []
  (run-jetty app {:port 3000 :join? false}))
