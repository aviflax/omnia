(ns omnia-poc.web
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [compojure.core :refer [defroutes GET]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [hiccup.page :refer [html5]]
            [hiccup.form :as f]
            [omnia-poc.lucene :as index]
            [clojure.string :refer [capitalize join split]]))

(defn handle-index []
  (html5 [:head
          [:title "Omnia"]]
         [:body
          [:header
           [:h1 "Omnia"]]
          (f/form-to [:get "/search"]
                     (f/text-field :q)
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
  (let [results (doall (index/search query))]
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
                [:h1
                 [:a {:href (link result)}
                  (:name result)]]
                [:label.path (:path result)]
                [:p.snippet (:snippet result) "…"]
                [:label.source
                 "("
                 (-> result :omnia-account-type-name capitalize-each-word)
                 ")"]
                [:hr]])]])))

(defroutes routes
           (GET "/" [] (handle-index))
           (GET "/search" [q] (handle-search q)))

(def app
  (-> routes
      wrap-params
      wrap-stacktrace))

(defn start []
  (run-jetty app {:port 3000 :join? false}))
