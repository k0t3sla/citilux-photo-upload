(ns citilux-photo-upload.upload
  (:require [clj-http.client :as client]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [cheshire.core :as ch]
            [config.core :refer [env]]
            [citilux-photo-upload.utils :refer [create-path-with-root send-message!]])
  (:gen-class))

(defn encode64 [path]
  (.encodeToString
   (java.util.Base64/getEncoder)
   (org.apache.commons.io.FileUtils/readFileToByteArray
    (io/file path))))

(defn upload-fotos
  "Грузим фото на сервер в base64"
  [art]
  (let [files (sort (mapv str (fs/glob (str (:out-path env) (create-path-with-root art "04_SKU_INTERNAL_1_1/")) "**{.jpeg,jpg}")))
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

(defn get-last-modified-file [directory pattern]
  (->> (fs/glob directory pattern)
       (sort-by fs/last-modified-time)
       last
       str))

(defn upload-3d
  "Грузим 3d на сервер в base64"
  [art]
  (let [directory (str (:out-path env) (create-path-with-root art "04_SKU_3D_FOR_DESIGNERS/"))
        pattern "**{.zip}"
        last-modified-file (get-last-modified-file directory pattern)
        file (when last-modified-file
                      (encode64 last-modified-file))
        data {:art art
              :file-name (fs/file-name last-modified-file)
              :file-3d file}
        _ (println data)
        resp (try
               (client/post (:url-3d env)
                            {:headers {"Authorization-Token" (:token-site env)}
                             :body (ch/generate-string data)
                             :insecure true
                             :content-type :json
                             :conn-timeout 300000})
               (catch Exception e (send-message! (str "Ошибка при загрузке архива 3D на сервер: " (.getMessage e)))))]
    (when (not= (:status resp) 200)
      (send-message! (str "проблемы при загрузке архива 3D - " art " status = " (:status resp))))))

(defn upload-manuals
  "Загружаем инструкции и схемы сборки на сервер"
  [instructions assembly]
  (when (or (seq instructions) (seq assembly)) ; выполняем только если есть данные
    (let [encode-file (fn [path]
                        (encode64 path))
          
          instructions-data (for [{:keys [article path]} instructions]
                            {:art article
                             :file-name (fs/file-name path)
                             :instruction (encode-file path)})
          
          assembly-data (for [{:keys [article path]} assembly]
                         {:art article
                          :file-name (fs/file-name path)
                          :assembly (encode-file path)})
          
          data {:instructions instructions-data
                :assembly assembly-data}
          
          _ (println data)
          
          resp (try
                 (client/post (:url-manuals env)
                            {:headers {"Authorization-Token" (:token-site env)}
                             :body (ch/generate-string data)
                             :insecure true
                             :content-type :json
                             :conn-timeout 300000})
                 (catch Exception e 
                   (send-message! (str "Ошибка при загрузке инструкций на сервер: " (.getMessage e)))))]
      
      (when (not= (:status resp) 200)
        (send-message! (str "Проблемы при загрузке инструкций, статус: " (:status resp)))))))

(comment
  (let [art "CL101161"
        directory (str (:out-path env) (create-path-with-root art "04_SKU_3D_FOR_DESIGNERS/"))
        pattern "**{.zip}"
        last-modified-file (get-last-modified-file directory pattern)
        file (when last-modified-file
               (encode64 last-modified-file))
        data {:art art
              :file-name (fs/file-name last-modified-file)
              :file-3d file}]
    (client/post "https://citilux.ru/api/upd/files3d/"
                 {:headers {"Authorization-Token" (:token-site env)}
                  :body (ch/generate-string data)
                  :insecure true
                  :content-type :json
                  :conn-timeout 300000}))

  )


