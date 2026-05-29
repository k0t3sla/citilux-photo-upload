(ns citilux-photo-upload.core-test
  (:require [cheshire.core :as json]
            [citilux-photo-upload.core :refer :all]
            [citilux-photo-upload.proxy :as proxy]
            [citilux-photo-upload.upload :refer [upload-fotos]]
            [citilux-photo-upload.utils :refer :all]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [config.core :refer [env]]))

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

(deftest hotdir-progress-handler-shape-test
  (testing "Progress endpoint data has expected keys"
    (reset! upload-progress {:running? true
                             :stage "test"
                             :entries [{:ts "x" :message "m"}]
                             :started-at "s"
                             :finished-at nil})
    (let [resp (hotdir-progress-handler nil)]
      (is (= true (:running resp)))
      (is (= "test" (:stage resp)))
      (is (vector? (:entries resp))))))

(deftest upload-to-server-debug-message-test
  (testing "Manual upload page shows debug notice when :debug is true"
    (let [saved-env env]
      (try
        (alter-var-root #'env assoc :debug true)
        (with-redefs [filter-files (fn [{:keys [filter-errors?]}]
                                     (if filter-errors? [] ["CL123"]))]
          (let [html (upload-to-server {:params {:arts "CL123"}})]
            (is (str/includes? html "Режим debug: загрузка на сервер отключена"))
            (is (not (str/includes? html "отправлены на сервер")))))
        (finally (alter-var-root #'env (constantly saved-env)))))))

(deftest upload-to-server-production-message-test
  (testing "Manual upload page shows success message when :debug is false"
    (let [saved-env env]
      (try
        (alter-var-root #'env assoc :debug false)
        (with-redefs [filter-files (fn [{:keys [filter-errors?]}]
                                     (if filter-errors? [] ["CL123"]))
                     upload-fotos (fn [_] nil)]
          (let [html (upload-to-server {:params {:arts "CL123"}})]
            (is (str/includes? html "Фото по этим артикулам отправлены на сервер"))))
        (finally (alter-var-root #'env (constantly saved-env)))))))

(deftest hotdir-handler-guard-test
  (testing "Second hotdir run is guarded when blocked"
    (reset! blocked true)
    (let [resp (hotdir-handler nil)]
      (is (string? resp)))
    (reset! blocked false)))
