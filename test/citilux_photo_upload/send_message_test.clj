(ns citilux-photo-upload.send-message-test
  (:require [clojure.test :refer :all]
            [citilux-photo-upload.utils :as utils]
            [citilux-photo-upload.proxy :as proxy]
            [config.core :as config]
            [clj-http.client :as client]))

(deftest send-message-falls-back-without-proxy
  (testing "When proxy list is empty, send-message! still sends without :proxy"
    (let [captured-opts (atom nil)]
      (with-redefs [proxy/candidate-request-proxies (fn [] [])
                    config/env {:tbot "bot-token" :chat_id 123}
                    client/post (fn [_url opts]
                                  (reset! captured-opts opts)
                                  {:status 200 :body {:ok true}})]
        (utils/send-message! "hello")
        (is (not (contains? @captured-opts :proxy)))))))
