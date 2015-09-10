(defproject omnia-poc "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "TBD"
            :url "TBD"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.datomic/datomic-free "0.9.5206"]      ; not available on clojars or maven central; must be added to local maven repository cache thingy
                 [clucy "0.4.0"]
                 [com.dropbox.core/dropbox-core-sdk "1.7" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [clj-http "2.0.0"]
                 [cheshire "5.5.0"]

                 ; these deps are not used directly but I’m specifying them to address conflicts
                 [commons-codec "1.10"]
                 [joda-time "2.8.2"]]
  :main omnia-poc.core
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[cursive/datomic-stubs "0.9.5153" :scope "provided"]]}
             :uberjar {:aot :all}})

