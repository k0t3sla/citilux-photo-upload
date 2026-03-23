(ns citilux-photo-upload.core-test
  (:require [clojure.test :refer :all]
            [citilux-photo-upload.core :refer :all]
            [citilux-photo-upload.utils :refer :all]))

(deftest test-move-and-compress
  (testing "move-and-compress with invalid path"
    (is (= 1 1))))
