(ns citilux-photo-upload.utils
  (:require [babashka.fs :as fs]
            [clj-http.client :as client]
            [cheshire.core :refer [generate-string]]
            [config.core :refer [env]]
            [clojure.walk :as walk]
            [mikera.image.core :as img]
            [clojure.string :as str])
  (:gen-class))

(defn send-message! [text]
  (client/post
   (str "https://api.telegram.org/bot" (:tbot env) "/sendMessage")
   {:body (generate-string {:chat_id (:chat_id env) :text text :parse_mode "HTML"})
    :headers {"Content-Type" "application/json"
              "Accept" "application/json"}}))

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

(defn filter-files-ext [files ext]
  (filter (fn [x]
            (= (fs/extension x) ext))
          files))

(defn create-art-link [[art q] msg]
  (if msg
    (str art " - " q " шт\n")
    (str "<a href=\"https://citilux.ru/store/" (str/lower-case art) "/\">" art "</a> - " q " шт\n")))

(defn process-files [files ext heading err?]
  (let [filtered-files (vec (filter-files-ext files ext))
        freq-files (into [] (frequencies (map get-article filtered-files)))
        out (for [v freq-files]
              (create-art-link v heading))]
    (when (not-empty out)
      (if err?
        (str ext " по следующим позициям - \n" (apply str out))
        (str "Добавлены новые " ext " по следующим позициям - \n" (apply str out))))))

(defn notify!
  "Перечисляем фото или видео с их колличеством"
  [{:keys [files heading err?]}]
  (let [file-types ["jpg" "mp4" "psd" "png"]
        msgs (for [file-type file-types]
               (process-files files file-type heading err?))
        msg (str (when heading heading) (apply str msgs))]
    (println msg)
    (when-not (str/blank? msg)
      (send-message! msg))))

(defn get-all-articles []
  (-> (client/post (str (:root-1c-endpoint env) (:get-all-articles-url env))
                   {:headers {"Authorization" (:token-1c env)}})
      :body
      parse-resp))

(defn report-imgs-1c! [arts]
  (when-not (= "OK" (-> (client/post (str (:root-1c-endpoint env) (:imgs-report-1c env))
                                    {:headers {"Authorization" (:token-1c-foto env)}
                                     :throw-exceptions false
                                     :body (generate-string arts)})
                       :reason-phrase))
    (send-message! "Ошибка отправки в 1с")))

(defn check-dimm [^String path]
  (let [img (img/load-image path)
        ^int w (try
                 (img/width img)
                 (catch Exception e (.getMessage e)))
        ^int h (try
                 (img/height img)
                 (catch Exception e (.getMessage e)))]
    {:path path :correct-dimm? (or (and (= w 2000) (or (= h 2000) (= h 2667)))
                                   false)}))


(defn get-stat-files [dir]
  (let [oz-path (str (:design env) dir)
        files (mapv str (fs/glob oz-path "**{.jpg,jpeg,png,mp4}"))
        file-groups (group-by get-article files)
        data (mapv (fn [[k v]] [k (mapv fs/file-name v)]) file-groups)]
    (walk/keywordize-keys (into {} data))))

(defn general-stat-handler []
  {:WB (get-stat-files "WB")
   :OZ (get-stat-files "OZON")})

(defn article-stat-handler [art]
  {:WB ((keyword art) (get-stat-files "WB"))
   :OZ ((keyword art) (get-stat-files "OZON"))})

(comment
  (report-imgs-1c! (mapv get-article ["CL704320"
                                      "CL704330"
                                      "CL704340"
                                      "CL704341"])))