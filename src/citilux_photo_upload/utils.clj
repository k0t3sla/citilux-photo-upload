(ns citilux-photo-upload.utils
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]
            [clj-http.lite.client :as client]
            [cheshire.core :refer [generate-string]]
            [config.core :refer [env]]
            [clojure.string :as str])
  (:gen-class))

(defn send-message [text]
  (client/post (str "https://api.telegram.org/bot" (:tbot env) "/sendMessage")
               {:body (generate-string {:chat_id (:chat_id env) :text text})
                :content-type :json
                :accept :json}))

(def delimiter
  "разделитель в зависимости от ос для создания пути"
  (if (fs/windows?)
    (str "\\")
    (str "/")))

(defn create-path
  "Созание пути для сохранения, первые 3 ,5 весь артикул"
  [art]
  (str (subs art 0 3) delimiter (subs art 0 5) delimiter art delimiter))

(defn get-article
  "Тримаем, и получаем артикула без нижних подчеркиваний"
  [file]
  (str/trim (first (str/split (fs/file-name file) #"_(?!.*_)"))))

(defn filter-files [files ext]
  (filter (fn [x]
            (= (fs/extension x) ext))
          files))

(defn notify
  "Перечисляем фото или видео с их колличеством"
  [files]
  (let [jpg (vec (filter-files files "jpg"))
        mp4 (vec (filter-files files "mp4"))
        psd (vec (filter-files files "psd"))
        png (vec (filter-files files "png"))
        freq-jpg (into [] (frequencies (map get-article jpg)))
        freq-mp4 (into [] (frequencies (map get-article mp4)))
        freq-psd (into [] (frequencies (map get-article psd)))
        freq-png (into [] (frequencies (map get-article png)))
        jpg-out (for [v freq-jpg]
                  (str (str/join " - " v) " шт\n"))
        mp4-out (for [v freq-mp4]
                  (str (str/join " - " v) " шт\n"))
        psd-out (for [v freq-psd]
                  (str (str/join " - " v) " шт\n"))
        png-out (for [v freq-png]
                  (str (str/join " - " v) " шт\n"))
        msg (str (when (not-empty jpg-out) (str "Добавлены новые фото по следующим позициям - \n" (apply str jpg-out)))
                 (when (not-empty mp4-out) (str "Добавлены новые видео по следующим позициям - \n" (apply str mp4-out)))
                 (when (not-empty psd-out) (str "Добавлены psd фото по следующим позициям - \n" (apply str mp4-out)))
                 (when (not-empty png-out) (str "Добавлены новые png по следующим позициям - \n" (apply str mp4-out))))]
    (println msg)
    (when-not (str/blank? msg)
      (send-message msg))))