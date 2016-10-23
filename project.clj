(defproject omnia "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "TBD"
            :url "TBD"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.395"]
                 [clojurewerkz/elastisch "3.0.0-beta1"]
                 [com.taoensso/timbre "4.7.4"]
                 [org.clojure/core.cache "0.6.5"]

                 ;; SLF4J is used by various libs and their specified versions rarely match up
                 [org.slf4j/slf4j-api "1.7.21"]

                 ;; Jackson is used by both Dropbox and Cheshire, and their specified versions conflict
                 [com.fasterxml.jackson.core/jackson-core "2.8.4"]
                 [com.fasterxml.jackson.core/jackson-annotations "2.8.4"]
                 [com.fasterxml.jackson.core/jackson-databind "2.8.4"]
                 [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor "2.8.4"]
                 [com.fasterxml.jackson.dataformat/jackson-dataformat-smile "2.8.4"]

                 ; Services
                 [com.dropbox.core/dropbox-core-sdk "2.1.2"]
                 [com.box/box-java-sdk "2.1.1"]

                 ; HTTP client stuff
                 [clj-http "3.3.0"]
                 [cheshire "5.6.3"]

                 [com.novemberain/pantomime "2.8.0"]

                 ; web UI stuff
                 [ring "1.5.0"]
                 [compojure "1.5.1"]
                 [hiccup "1.0.5"]

                 ; these deps are not used directly but I’m specifying them to address conflicts
                 [commons-codec "1.10"]
                 [joda-time "2.9.4"]]
  :main omnia.core
  :target-path "target/%s"
  :aliases {"test" ["midje"]}
  :profiles {:dev {:dependencies [[midje "1.8.3"]
                                  [lein-midje "3.2.1"]]}
             :uberjar {:aot :all}})
