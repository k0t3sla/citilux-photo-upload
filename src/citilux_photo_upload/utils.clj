(ns citilux-photo-upload.utils
  (:require [babashka.fs :as fs]
            [clj-http.client :as client]
            [cheshire.core :refer [generate-string]]
            [config.core :refer [env]]
            [clojure.string :as str])
  (:gen-class))

(defn send-message [text]
  (client/post (str "https://api.telegram.org/bot" (:tbot env) "/sendMessage")
               {:body (generate-string {:chat_id (:chat_id env) :text text :parse_mode "HTML"})
                :content-type :json 
                :accept :json}))

(def delimiter
  "разделитель в зависимости от ос для создания пути"
  (if (fs/windows?)
    (str "\\")
    (str "/")))

(defn parse-resp [resp]
  (str/split (apply str (->> resp
                             (drop 4)
                             (drop-last 4))) #"\\\",\\\""))

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

(defn create-art-link [[art q] wb?]
  (if (true? wb?)
    (str art " - " q " шт\n")
    (str "<a href=\"https://citilux.ru/store/" (str/lower-case art) "/\">" art "</a> - " q " шт\n")))

(defn notify
  "Перечисляем фото или видео с их колличеством"
  [files & [wb?]]
  (let [jpg (vec (filter-files files "jpg"))
        mp4 (vec (filter-files files "mp4"))
        psd (vec (filter-files files "psd"))
        png (vec (filter-files files "png"))
        freq-jpg (into [] (frequencies (map get-article jpg)))
        freq-mp4 (into [] (frequencies (map get-article mp4)))
        freq-psd (into [] (frequencies (map get-article psd)))
        freq-png (into [] (frequencies (map get-article png)))
        jpg-out (for [v freq-jpg]
                  (create-art-link v wb?))
        mp4-out (for [v freq-mp4]
                  (create-art-link v wb?))
        psd-out (for [v freq-psd]
                  (create-art-link v wb?))
        png-out (for [v freq-png]
                  (create-art-link v wb?))
        msg (str (when wb?
                   "В папку WEB+1C_wildberries\n")
                 (when (not-empty jpg-out) (str "Добавлены новые фото по следующим позициям - \n" (apply str jpg-out)))
                 (when (not-empty mp4-out) (str "Добавлены новые видео по следующим позициям - \n" (apply str mp4-out)))
                 (when (not-empty psd-out) (str "Добавлены psd фото по следующим позициям - \n" (apply str psd-out)))
                 (when (not-empty png-out) (str "Добавлены новые png по следующим позициям - \n" (apply str png-out))))]
    (println msg)
    (when-not (str/blank? msg)
      (send-message msg))))

(defn get-all-articles []
  (-> (client/post (:get-all-articles-url env)
                   {:headers {"Authorization" (:token-1c env)}})
      :body
      parse-resp))