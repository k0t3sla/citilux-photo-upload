(defproject citilux-photo-upload "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.babashka/http-client "0.1.4"]
                 [cheshire "5.11.0"]
                 [babashka/fs "0.2.16"]
                 [com.dorkbox/MinLog-SLF4J "2.0"]
                 [commons-io/commons-io "2.11.0"]
                 [de.digitalcollections.imageio/imageio-turbojpeg "0.6.2"]
                 [yogthos/config "1.2.0"]]
  :main ^:skip-aot citilux-photo-upload.core 
  :jvm-opts ["-Dconfig=config.edn"]   
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
