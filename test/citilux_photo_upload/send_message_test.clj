(ns citilux-photo-upload.send-message-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [config.core :refer [env]]
            [citilux-photo-upload.utils :as utils]))

(def ^:private saved-env (atom nil))

(use-fixtures :each
  (fn [f]
    (reset! saved-env env)
    (try (f)
         (finally (alter-var-root #'env (constantly @saved-env))))))

(deftest send-message-enqueues-message
  (testing "send-message! adds a pending message to the queue"
    (alter-var-root #'env assoc :debug false)
    (reset! utils/messages-queue [])
    (let [before-count (count @utils/messages-queue)]
      (utils/send-message! "test-enqueue")
      (let [msgs @utils/messages-queue
            added (last msgs)]
        (is (= (inc before-count) (count msgs)))
        (is (= "test-enqueue" (:text added)))
        (is (= :pending (:status added)))
        (is (= 0 (:attempts added)))))))

(deftest send-message-skipped-in-debug
  (testing "send-message! does not enqueue when :debug is true"
    (alter-var-root #'env assoc :debug true)
    (reset! utils/messages-queue [])
    (let [result (utils/send-message! "test-debug")]
      (is (= :debug-skipped (:status result)))
      (is (empty? @utils/messages-queue)))))
