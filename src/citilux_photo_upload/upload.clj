(ns citilux-photo-upload.upload
  (:require [clj-http.client :as client]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [cheshire.core :as ch]
            [config.core :refer [env]]
            [citilux-photo-upload.utils :refer [create-path send-message!]])
  (:gen-class))

(defn encode64 [path]
  (.encodeToString
   (java.util.Base64/getEncoder)
   (org.apache.commons.io.FileUtils/readFileToByteArray
    (io/file path))))

(defn upload-fotos
  "Грузим фото на сервер в base64"
  [art]
  (let [files (sort (mapv str (fs/glob (str (:out-path env) (create-path art)) "**{.jpeg,jpg}")))
        detail-foto (when (> (count files) 0)
                      (encode64 (first files)))
        encoded-fotos (when (> (count files) 1) ;; остальные фото исключая детальную
                        (mapv encode64 (drop 1 files)))
        data {:art art
              :detail detail-foto
              :photos encoded-fotos}
        resp (try
               (client/post (:url env)
                            {:headers {"Authorization-Token" (:token-site env)}
                             :body (ch/generate-string data)
                             :insecure true
                             :content-type :json
                             :conn-timeout 300000})
               (catch Exception e (send-message! (str "Ошибка при загрузке фото из WEB+1C на сервер: " (.getMessage e)))))]
    (when (not= (:status resp) 200)
      (send-message! (str "проблемы при загрузке фотографий - " art " status = " (:status resp))))))