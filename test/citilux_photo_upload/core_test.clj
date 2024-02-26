(ns citilux-photo-upload.core-test
  (:require [clojure.test :refer :all]
            [babashka.fs :as fs]
            [citilux-photo-upload.core :refer :all]
            [citilux-photo-upload.utils :refer :all]))

(deftest test-move-and-compress
  (testing "move-and-compress with invalid path"
    (is (thrown? Exception (move-and-compress "fakepath" ["/out"]))))

  (testing "move-and-compress with compression ratio > 0.9"
    (with-redefs [fs/size (constantly 100)
                  fs/copy (constantly nil)]
      (let [file "test.jpg"
            args ["/out"]]
        (move-and-compress file args)
        (is (= (fs/copy file (str "/out" file)) nil)))))

  (testing "move-and-compress with compression ratio < 0.9"
    (with-redefs [fs/size (constantly 50)
                  fs/copy (constantly nil)]
      (let [file "test.jpg"
            args ["/out"]]
        (move-and-compress file args)
        (is (= (fs/copy "tmp/test.jpg" (str "/out/test.jpg")) nil))))))

(deftest test-copy-file
  (testing "copy file to output dirs"
    (with-redefs [fs/copy (constantly nil)]
      (let [file "test.jpg"
            args ["/out1" "/out2"]]
        (copy-file file args)
        (is (= (fs/copy file "/out1/test.jpg") nil))
        (is (= (fs/copy file "/out2/test.jpg") nil))))))

(deftest test-filter-files
  (testing "filter files by errors"
    (with-redefs [get-article (constantly "CL123")]
      (let [files ["file1.jpg" "file2.jpg"]
            filtered (filter-files {:filter-errors? true
                                    :files files})]
        (is (= filtered files)))))

  (testing "filter files by include"
    (with-redefs [get-article (constantly "IN123")]
      (let [files ["file1.jpg" "file2.jpg"]
            filtered (filter-files {:include-in? true
                                    :files files})]
        (is (= filtered files))))))


(def test-files
  ["foo/bar/CL802001_1.jpg"
   "foo/bar/CL802003_1.jpg"
   "foo/bar/CL999999_1.jpg"
   "foo/bar/IN66666_1.jpg"
   "foo/bar/IN80110_1.jpg"
   "foo/bar/IN80111_1.jpg"])

(filter-files {:files test-files :include-in? false :filter-errors? false})
(filter-files {:files test-files :include-in? true :filter-errors? false})
(filter-files {:files test-files :include-in? false :filter-errors? true})
(filter-files {:files test-files :include-in? true :filter-errors? true})
