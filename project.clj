(defproject omnia "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "TBD"
            :url "TBD"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.374"]
                 [clojurewerkz/elastisch "2.2.1"]
                 [com.taoensso/timbre "4.3.0"]
                 [org.clojure/core.cache "0.6.4"]

                 ;; Jackson is used by both Dropbox and Cheshire and their specified versions conflict
                 [com.fasterxml.jackson.core/jackson-core "2.7.3"]
                 [com.fasterxml.jackson.core/jackson-annotations "2.7.3"]
                 [com.fasterxml.jackson.core/jackson-databind "2.7.3"]
                 [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor "2.7.3"]
                 [com.fasterxml.jackson.dataformat/jackson-dataformat-smile "2.7.3"]

                 ; Services
                 [com.dropbox.core/dropbox-core-sdk "2.0.1" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [com.box/box-java-sdk "2.1.1"]

                 ; HTTP client stuff
                 [clj-http "2.0.1"]
                 [cheshire "5.5.0"]

                 [com.novemberain/pantomime "2.8.0"]

                 ; web UI stuff
                 [ring/ring-core "1.4.0"]
                 [ring/ring-devel "1.4.0"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [compojure "1.4.0"]
                 [hiccup "1.0.5"]

                 ; these deps are not used directly but I’m specifying them to address conflicts
                 [commons-codec "1.10"]
                 [joda-time "2.9.2"]]
  :main omnia.core
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[midje "1.8.3"]
                                  [lein-midje "3.2"]]}
             :uberjar {:aot :all}})
