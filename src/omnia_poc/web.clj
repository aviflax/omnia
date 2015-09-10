(ns omnia-poc.web
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [compojure.core :refer [defroutes GET]]
            [ring.middleware.params :refer [wrap-params]]
            [hiccup.page :refer [html5]]
            [hiccup.form :as f]
            [omnia-poc.core :refer [search]]))

(defn handle-index []
  (html5 [:head
          [:title "Omnia"]]
         [:body
          [:header
           [:h1 "Omnia"]]
          (f/form-to [:get "/search"]
                     (f/text-field :q)
                     (f/submit-button "Search"))]))

(defn ^:private trunc
  [s n]
  (subs s 0 (min (count s) n)))

(defn handle-search [query]
  (let [results (search query)]
    (html5 [:head
            [:title "Omnia"]]
           [:body
            [:header
             [:h1 "Omnia"]
             (f/form-to [:get "/search"]
                        (f/text-field :q query)
                        (f/submit-button "Search"))]
            [:section#results

             (for [result results]
               [:section.result
                [:h1 (:name result)]
                [:p.snippet (trunc (:text result) 100) "…"]
                ])
             ]
            ])))

(defroutes routes
           (GET "/" [] (handle-index))
           (GET "/search" [q] (handle-search q)))

(def app
  (wrap-params routes))

(defn start []
  (run-jetty app {:port 3000 :join? false}))
