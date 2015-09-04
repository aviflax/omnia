(defproject omnia-poc "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "TBD"
            :url "TBD"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.datomic/datomic-free "0.9.5206"] ; not available on clojars or maven central; must be added to local maven repository cache thingy
                 [clucy "0.4.0"]
                 [clj-http "2.0.0"]
                 [cheshire "5.5.0"]
                 [com.dropbox.core/dropbox-core-sdk "[1.7,1.8)"]

                 ; these deps are not used directly but I’m specifying them to address conflicts
                 [commons-codec "1.10"]]
  :main omnia-poc.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})

