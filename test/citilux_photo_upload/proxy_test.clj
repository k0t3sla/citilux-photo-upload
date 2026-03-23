(ns citilux-photo-upload.proxy-test
  (:require [clojure.test :refer :all]
            [citilux-photo-upload.proxy :as proxy]
            [babashka.fs :as fs]))

(deftest parse-proxy-link-test
  (testing "Parses socks and mtproxy links"
    (let [socks (proxy/parse-proxy-link "https://t.me/socks?server=1.2.3.4&port=1080&user=u&pass=p")
          mt (proxy/parse-proxy-link "https://t.me/proxy?server=5.6.7.8&port=443&secret=abc")]
      (is (= :socks (:type socks)))
      (is (= "1.2.3.4" (:server socks)))
      (is (= 1080 (:port socks)))
      (is (= :mtproxy (:type mt)))
      (is (= 443 (:port mt))))))

(deftest add-remove-proxy-test
  (testing "Adds and removes proxies"
    (reset! proxy/proxy-state {:proxies []})
    (let [added (proxy/add-proxies! ["https://t.me/proxy?server=10.0.0.1&port=443&secret=x"])]
      (is (= 1 (:added added)))
      (is (= 1 (count (proxy/list-proxies)))))
    (let [id (:id (first (proxy/list-proxies)))
          removed (proxy/remove-proxies! [id])]
      (is (= 0 (:total removed)))
      (is (empty? (proxy/list-proxies))))))

(deftest get-working-proxy-test
  (testing "Returns raw-url of alive proxy"
    (reset! proxy/proxy-state
            {:proxies [{:id "a" :raw-url "https://t.me/proxy?server=a&port=1" :request-proxy "a:1" :alive? false}
                       {:id "b" :raw-url "https://t.me/proxy?server=b&port=2" :request-proxy "b:2" :alive? true}]})
    (is (= "https://t.me/proxy?server=b&port=2" (proxy/get-working-proxy-url)))))

(deftest save-load-test
  (testing "Serializes and deserializes proxy storage"
    (let [tmp-dir (str "/tmp/proxy-test-" (System/currentTimeMillis))
          storage-file (str tmp-dir "/proxies.edn")]
      (fs/create-dirs tmp-dir)
      (with-redefs [citilux-photo-upload.proxy/storage-path (fn [] storage-file)]
        (reset! proxy/proxy-state {:proxies [{:id "x" :raw-url "u" :request-proxy "h:1"}]})
        (proxy/save-proxies!)
        (reset! proxy/proxy-state {:proxies []})
        (proxy/load-proxies!)
        (is (= "x" (-> (proxy/list-proxies) first :id))))
      (fs/delete-tree tmp-dir))))
