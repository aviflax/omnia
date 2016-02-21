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
   [:a {:href "/accounts"} "Accounts"]
   (f/form-to [:post "/logout"]
              (f/submit-button "Log out"))])

(defn ^:private index-get []
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
  (or (:alternateLink doc)                                  ; google drive uses alternateLink
      (let [path-segments (split (:omnia-id doc) #"/")
            dir-path (join "/" (butlast path-segments))]
        (str "https://www.dropbox.com/home" dir-path "?preview=" (last path-segments)))))

(defn ^:private capitalize-each-word [s]
  (as-> ((fnil split "") s #" ") it                         ;; TEMP TEMP UGLY HACK TEMP TEMP
        (map capitalize it)
        (join " " it)))

(defn ^:private pagination-links [query total current-page per-page]
  (when (> total per-page)
        (let [num-pages (-> (/ total per-page) Math/ceil int)]
          [:ol#pagination
           (for [page-num (range 1 (inc num-pages))]
             [:li
              (if (= page-num current-page)
                  current-page
                  [:a {:href (str "/search?q=" query
                                  "&page=" page-num
                                  "&per-page=" per-page)}
                   page-num])])])))

(defn ^:private search-get [query page-num per-page]
  (if (blank? (trim query))
      (redirect "/" 307)
      (let [result (index/search query page-num per-page)
            total (:total result)]
        (html5 [:head
                [:title "Search for “" query "” « Omnia"]
                [:style "p#total { font-size: smaller; }
                         section.result { margin: 20px 0; }
                         section.result * { margin: 2px 0; padding: 0; }
                         ol#pagination { padding: 0; }
                         ol#pagination li { list-style-type: none; display: inline; padding-right: 10px; }"]]
               [:body
                (header)
                (f/form-to [:get "/search"]
                           (search-field query)
                           (f/submit-button "Search"))
                [:p#total (when (> total 20) "About ")
                 total " result" (when (not= total 1) "s")]
                [:section#results
                 (for [hit (:hits result)
                       :let [path (:path hit)
                             path-segments (when path (split path #"/"))
                             path (join "/" (butlast path-segments))]]
                   [:section.result
                    [:h1
                     [:a {:href (link hit)}
                      (:name hit)]]
                    [:label.source (-> hit :omnia-service-name capitalize-each-word)]
                    (when-not (blank? path) ": ")
                    [:label.path path]
                    [:p.highlight (:highlight hit) "…"]])]
                (pagination-links query total page-num per-page)
                footer]))))

(defn ^:private accounts-get [{user :user :as session}]
  (html5 [:head
          [:title "Accounts « Omnia"]
          [:style "form { display: inline; margin-left: 1em;}"]]
         [:body
          (header "Accounts")
          (if-let [accounts (seq (db/get-accounts-for-user (:id user)))]
            [:ul
             (for [account accounts]
               [:li (-> account :service :display-name)
                (f/form-to [:delete (str "/accounts/" (url-encode (:id account)))]
                           (f/submit-button "Disconnect"))])]
            [:p "Oh no! You have no accounts connected! You should definitely connect one!"])
          [:a {:href "/accounts/connect"} "Connect a New Account"]]))

(defn ^:private accounts-connect-get []
  ;; TODO: only show services that the user doesn’t already have connected
  (html5 [:head
          [:title "Connect a New Account « Omnia"]]
         [:body
          (header "<a href=\"/accounts\">Accounts</a> » Connect a New Account")
          [:section
           [:h1 "Which type of Account would you like to connect?"]
           [:ol
            [:li [:a {:href "/accounts/connect/box/start"} "Box"]]
            [:li [:a {:href "/accounts/connect/dropbox/start"} "Dropbox"]]
            [:li [:a {:href "/accounts/connect/google-drive/start"} "Google Drive"]]]
           [:p "Please choose a service and we’ll explain more about how this works."]
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
                     "box"
                     [:li "help text coming soon!"]

                     "dropbox"
                     (seq [[:li "If you choose to continue, we’ll direct you to " service-name ", who will ask you whether
                                 you’d like to give us permission to access your documents."]

                           [:li "Dropbox doesn’t offer a way for us to request read-only access, so if you want us to index
                                 your documents, we’ll need full access to your Dropbox account — but we promise that we will
                                 only ever <i>read</i> your Dropbox data, never write to it."]

                           [:li "And we’ll only index the documents in your “team” folder, which are already shared with
                                 your entire team/organization."]])

                     "google-drive"
                     (seq [[:li "If you choose to continue, we’ll direct you to " service-name ", who will ask you whether
                                 you’d like to give us permission to <b>read</b> your documents."]

                           [:li "We’ll only read the documents in your Google Drive that are shared with your entire
                                 organization — that is, those documents that everyone in the organization can
                                 <em>already</em> find and view within Google Drive."]])

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

(defn ^:private accounts-connect-service-finish-get [session service-slug auth-code state]
  (cond
    (not= state "TODO")
    {:status  500                                           ; not actually an internal server error but I don’t want to reveal to an attacker what the problem is
     :headers {"Content-Type" "text/plain"}
     :body    "Internal Server Error"}

    (blank? auth-code)
    bad-request

    :default
    (let [token-response (-> (get-access-token "accounts/connect" service-slug auth-code)
                             :body)]
      (if (blank? (:access_token token-response))
          bad-request
          (let [access-token (:access_token token-response)
                service (db/get-service service-slug)
                user-account (get-user-account service access-token)
                omnia-account-id (db/account-id service-slug (:id user-account))
                ;; TODO: confirm that the user actually connected a work account (Dropbox)
                ;; TODO: check the account userid and don’t create a duplicate new account if it’s already connected
                ;; TODO: should probably include the account userid (e.g. the Dropbox userid) in the account
                account (-> {:id            omnia-account-id
                             :user          (:user session)
                             :service       service
                             :access-token  access-token
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
             [:p "We’ve started indexing the documents in this account."]
             [:p "Would you like us to send you an email when we’re done? <button onclick=\"alert('coming soon!')\">Yes, please</button>"]
             [:p [:a {:href "/accounts"} "Back to Accounts"]]]])))

(defn ^:private account-delete [id]
  ;; TODO: make this async. Sure, I could just wrap it with `future`, but then the user
  ;; would navigate back to /accounts and would still see the account they asked to disconnect.
  ;; I’ll need some way to mark an account as “disconnect in progress”
  (accounts.util/disconnect id)
  ;; TODO: maybe refresh ES DB index?
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
           [:p "Please choose a service and we’ll explain more about how this works."]
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
                existing-omnia-user (db/get-user {:email      (:email user-account)
                                                  :account-id omnia-account-id})
                new-omnia-user (when-not existing-omnia-user
                                 (db/create-user user-account))
                omnia-user (or existing-omnia-user new-omnia-user)
                ;; TODO: should probably include the account userid (e.g. the Dropbox userid) in the account
                account (or (db/get-account omnia-account-id)
                            (-> {:id            omnia-account-id
                                 :user          omnia-user
                                 :service       service
                                 :access-token  access-token
                                 :refresh-token (:refresh_token token-response)}
                                map->Account
                                init
                                db/create-account))]
            (future
              (try (accounts/sync account)
                   (catch Exception e (println e))))
            (-> (redirect (if new-omnia-user "/welcome" "/")
                          303)
                (assoc-in [:session :user] omnia-user)))))))

(defn ^:private welcome-get []
  ;; TODO: this is pretty duplicative with index-get
  (html5 [:head
          [:title "Welcome to Omnia!"]]
         [:body
          (header)
          [:h1 "Welcome to Omnia!"]
          [:p "We’re so happy to have you here. MORE FRIENDLY HELP HERE!!!"]
          (f/form-to [:get "/search"]
                     (search-field "")
                     (f/submit-button "Search"))
          footer]))

(defn logout-post []
  (-> (redirect "/goodbye" 303)
      (assoc :session nil)))

(defn ^:private goodbye-get []
  (html5 [:head
          [:title "Goodbye! « Omnia"]]
         [:body
          (header "Goodbye!")
          [:p "Thanks for using Omnia, have a great day!"]
          [:p
           "(Did you log out by accident? Sorry about that! "
           [:a {:href "/login"} "Log back in."]
           ")"]
          [:section
           {:style "margin-top: 3em; border-top: 1px dashed silver;"}
           [:h1 "BTW"]
           [:p "Got any questions or suggestions for us?"]
           [:textarea {:rows 3 :cols 40 :style "display: block;"}]
           [:p "Would it be OK for us to follow up with you?"]
           [:p "If so, please enter your email address: "
            (f/email-field "email")]
           (f/submit-button "Fire away!")]]))

(defn wrap-restricted [handler]
  (fn [{session :session :as req}]
    (if (nil? (:user session))
        (redirect "/login" 307)
        (handler req))))

(def restricted-routes
  (wrap-restricted
    (routes
      (GET "/" [] (index-get))
      (GET "/welcome" [] (welcome-get))

      (GET "/search" {{q   "q", page "page", per-page "per-page"
                       :or {q "", page "1", per-page "10"}} :params}
        (search-get q (Integer/parseInt page) (Integer/parseInt per-page)))

      (GET "/accounts" {session :session} (accounts-get session))
      (GET "/accounts/connect" [] (accounts-connect-get))
      (GET "/accounts/connect/:service-slug/start" [service-slug] (accounts-connect-service-start-get service-slug))
      (GET "/accounts/connect/:service-slug/finish" [service-slug code state
                                                     :as {session :session}]
        (accounts-connect-service-finish-get session service-slug code state))
      (GET "/accounts/connect/:service-slug/done" [service-slug] (accounts-connect-service-done-get service-slug))
      (DELETE "/accounts/:id" [id] (account-delete id)))))

(defroutes open-routes
           (GET "/login" [] (login-get))
           (GET "/login/with/:service-slug/start" [service-slug] (login-start-get service-slug))
           (GET "/login/with/:service-slug/finish" [service-slug code state] (login-finish-get service-slug code state))
           (POST "/logout" [] (logout-post))
           (GET "/goodbye" [] (goodbye-get)))

(def app
  (-> (routes open-routes
              restricted-routes)
      (wrap-session {:store (cookie-store {:key "a 16-byte secret"})})
      wrap-params
      wrap-stacktrace))

(def ^:private server (atom nil))

(defn start []
  (reset! server
          (run-jetty app {:port 3000 :join? false})))

(defn stop []
  (.stop @server)
  (reset! server nil))
