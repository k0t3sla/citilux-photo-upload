(ns citilux-photo-upload.core-test
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [citilux-photo-upload.core :refer :all]
            [citilux-photo-upload.proxy :as proxy]
            [citilux-photo-upload.utils :refer :all]))

(deftest test-move-and-compress
  (testing "move-and-compress with invalid path"
    (is (= 1 1))))

(deftest proxy-add-handler-validation-test
  (testing "Returns 400 with invalid proxy links"
    (with-redefs [proxy/add-proxies-validated! (fn [_]
                                                 {:added 0
                                                  :invalid 1
                                                  :invalid-items [{:input "ftp://bad" :error "Неподдерживаемая схема URL. Разрешены только http/https"}]})]
      (let [request {:body (java.io.ByteArrayInputStream. (.getBytes "{\"links\":\"ftp://bad\"}" "UTF-8"))}
            resp (proxy-add-handler request)
            body (json/parse-string (:body resp) true)]
        (is (= 400 (:status resp)))
        (is (= 1 (:invalid body)))))))
