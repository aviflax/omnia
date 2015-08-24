(defproject omnia-poc "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [clucy "0.4.0"]
                 [com.dropbox.core/dropbox-core-sdk "[1.7,1.8)"]
                 [com.google.apis/google-api-services-drive "v2-rev182-1.20.0"]]
  :main omnia-poc.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})

