(defproject citilux-photo-upload "1.0"
  :description "Process content files citilux"
  :url "http://citilux.ru/"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [cheshire "5.11.0"]
                 [babashka/fs "0.2.16"]
                 [http-kit "2.7.0"]
                 [metosin/reitit "0.3.9"] 
                 [mount/mount "0.1.17"]
                 [compojure "1.7.0"]
                 [hiccup "1.0.5"]
                 [schejulure "1.0.1"]
                 ;;[org.clojure/core.async "1.6.681"]
                 
                 [clj-http "3.12.3"]
                 [net.mikera/imagez "0.12.0"] 

                 [ring/ring-defaults "0.3.2"]

                 [commons-io/commons-io "2.11.0"]
                 [yogthos/config "1.2.0"]]
  :main ^:skip-aot citilux-photo-upload.core 
  :jvm-opts ["-Dconfig=config.edn"]   
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
