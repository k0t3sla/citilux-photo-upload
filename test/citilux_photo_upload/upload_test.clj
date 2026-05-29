(ns citilux-photo-upload.upload-test
  (:require [babashka.fs :as fs]
            [citilux-photo-upload.upload :as upload]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [config.core :refer [env]]))

(def ^:private saved-env (atom nil))

(use-fixtures :each
  (fn [f]
    (reset! saved-env env)
    (try (f)
         (finally (alter-var-root #'env (constantly @saved-env))))))

(deftest get-last-modified-file-returns-nil-when-empty
  (testing "nil when directory has no matching files"
    (let [dir (fs/create-temp-dir)
          result (upload/get-last-modified-file (str dir) "**{.zip}")]
      (is (nil? result))
      (fs/delete-tree dir))))

(deftest upload-3d-skips-when-no-zip
  (testing "does not throw when no zip file exists"
    (alter-var-root #'env assoc :debug false :out-path "/tmp/nonexistent-upload-test/")
    (is (nil? (upload/upload-3d "CL123")))))
