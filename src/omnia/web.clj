(ns omnia.web
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [compojure.core :refer [defroutes GET]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.util.response :refer [redirect]]
            [hiccup.page :refer [html5]]
            [hiccup.form :as f]
            [omnia.index :as index]
            [clojure.string :refer [blank? capitalize join split trim]]))

(defn ^:private search-field [query]
  [:input {:type      "search"
           :name      "q"
           :id        "q"
           :value     query
           :required  "" ; save a few bytes!
           :autofocus ""}])

(defn handle-index []
  (html5 [:head
          [:title "Omnia"]]
         [:body
          [:header
           [:h1 "Omnia"]]
          (f/form-to [:get "/search"]
                     (search-field "")
                     (f/submit-button "Search"))]))

(defn link [file]
  (if (contains? file :alternateLink)
      (:alternateLink file)
      (let [path-segments (split (:omnia-file-id file) #"/")
            dir-path (join "/" (butlast path-segments))]
        (str "https://www.dropbox.com/home" dir-path "?preview=" (last path-segments)))))

(defn capitalize-each-word [s]
  (as-> ((fnil split "") s #" ") it                         ;; TEMP TEMP UGLY HACK TEMP TEMP
        (map capitalize it)
        (join " " it)))

(defn handle-search [query]
  (if (blank? (trim query))
      (redirect "/" 307)
      (let [results (doall (index/search query))]
        (html5 [:head
                [:title "Omnia"]]
               [:body
                [:header
                 [:h1 "Omnia"]
                 (f/form-to [:get "/search"]
                            (search-field query)
                            (f/submit-button "Search"))]
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
                     (-> result :omnia-account-type-name capitalize-each-word)
                     ")"]
                    [:hr]])]]))))

(defroutes routes
           (GET "/" [] (handle-index))
           (GET "/search" [q] (handle-search q)))

(def app
  (-> routes
      wrap-params
      wrap-stacktrace))

(defn start []
  (run-jetty app {:port 3000 :join? false}))
