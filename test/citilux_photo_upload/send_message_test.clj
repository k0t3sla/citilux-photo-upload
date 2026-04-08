(ns citilux-photo-upload.send-message-test
  (:require [clojure.test :refer [deftest is testing]]
            [citilux-photo-upload.utils :as utils]))

(deftest send-message-enqueues-message
  (testing "send-message! adds a pending message to the queue"
    (reset! utils/messages-queue [])
    (let [before-count (count @utils/messages-queue)]
      (utils/send-message! "test-enqueue")
      (let [msgs @utils/messages-queue
            added (last msgs)]
        (is (= (inc before-count) (count msgs)))
        (is (= "test-enqueue" (:text added)))
        (is (= :pending (:status added)))
        (is (= 0 (:attempts added)))))))
