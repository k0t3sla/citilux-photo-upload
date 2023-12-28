(defproject citilux-photo-upload "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [cheshire "5.11.0"]
                 [babashka/fs "0.2.16"]
                 [http-kit "2.7.0"]
                 [metosin/reitit "0.3.9"]
                 [ring/ring-defaults "0.3.2"]
                 [mount/mount "0.1.17"]
                 [compojure "1.7.0"]
                 [hiccup "1.0.5"]
                 [schejulure "1.0.1"]
                 
                 [clj-http "3.12.3"]
                 [com.github.jai-imageio/jai-imageio-core "1.4.0"]

                 [metosin/reitit "0.3.9"]
                 [ring/ring-defaults "0.3.2"]

                 [commons-io/commons-io "2.11.0"]
                 [yogthos/config "1.2.0"]]
  :main ^:skip-aot citilux-photo-upload.core 
  :jvm-opts ["-Dconfig=config.edn"]   
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}}
  
  :plugins [[io.taylorwood/lein-native-image "0.3.0"]
            [nrepl/lein-nrepl "0.3.2"]]
  :native-image {:name     "app"
                 :jvm-opts ["-Dclojure.compiler.direct-linking=true"]
                 :opts     ["--enable-url-protocols=http"
                            "--report-unsupported-elements-at-runtime"
                            "--initialize-at-build-time"
                            "--allow-incomplete-classpath"
                              ;;avoid spawning build server
                            "--no-server"
                            "-H:ConfigurationResourceRoots=resources"
                            ~(str "-H:ResourceConfigurationFiles="
                                  (System/getProperty "user.dir")
                                  (java.io.File/separator)
                                  "config.edn")]})
