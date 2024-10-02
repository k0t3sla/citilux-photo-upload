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

#_(defn get-article [file]
  (let [ext (fs/extension file)
        exts #{"mp4" "png" "psd" "jpg" "jpeg"}]
    (if (exts ext)
      (subs file 0 (str/last-index-of file "_"))
      file)))

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

(defn count-files-with-extension [file-list]
  (->> file-list
       (mapv fs/extension)
       frequencies))

(defn get-stat-files [dir]
  (let [oz-path (str (:design env) dir)
        files (mapv str (fs/glob oz-path "**{.jpg,jpeg,png,mp4}"))
        file-groups (group-by get-article files)
        data (mapv (fn [[k v]] [k (count-files-with-extension v)]) file-groups)]
    (walk/keywordize-keys (into {} data))))

(defn general-stat-handler []
  {:WB (get-stat-files "WB")
   :OZ (get-stat-files "OZON")})

(defn article-stat-handler [art]
  {:WB ((keyword art) (get-stat-files "WB"))
   :OZ ((keyword art) (get-stat-files "OZON"))})

(defn inlux? [s]
  (str/starts-with? (get-article s) "IN"))

(defn exist? [file articles]
  (some #(= (get-article file) %) articles))

(defn copy-file [file args]
  (let [name (fs/file-name file)
        art (get-article name)
        path (str (create-path art) name)]
    (doseq [arg args]
      (fs/create-dirs (str arg (create-path art)))
      (fs/copy file (str arg path) {:replace-existing true}))))

(defn move-file [file args]
  (let [name (fs/file-name file)
        art (get-article name)
        path (str (create-path art) name)]
    (doseq [arg args]
      (fs/create-dirs (str arg (create-path art)))
      (fs/copy file (str arg path) {:replace-existing true}))
    (fs/delete-if-exists file)))


(defn split-articles [^String s]
  (filter #(not (str/blank? %)) (str/split s #",|\n|\t")))

(comment



  ;;;; DIR GENERATOR

  (def dirs [["0_TEST_REPORTS" "C" "01_PRODUCTION_FILES"]
             ["01_3D"	"C" "01_PRODUCTION_FILES"]
             ["01_ABRIS"	"A" "01_PRODUCTION_FILES"]
             ["02_BOX"	"C" "01_PRODUCTION_FILES"]
             ["02_LABELS"	"A" "01_PRODUCTION_FILES"]
             ["02_MASTER_LABELS"	"C" "01_PRODUCTION_FILES"]
             ["03_ASSEMBLY"	"A" "01_PRODUCTION_FILES"]
             ["03_MANUAL"	"A" "01_PRODUCTION_FILES"]
             ["04_OTHER"	"C" "01_PRODUCTION_FILES"]
             ["02_BANK_PHOTO"	"A"]
             ["02_BANK_VIDEO_SHORTS"	"A"]
             ["03_MAKET_VERSTKI"	"A"]
             ["03_PHOTO_PNG"	"A"]
             ["03_PHOTO_PSD_ RGB"	"A"]
             ["03_SOURCE_1_1"	"A"]
             ["03_SOURCE_3_4"	"A"]
             ["04_SKU_1C"	"A"]
             ["04_SKU_PNG_WHITE"	"A"]
             ["04_SKU_3D_FOR_DESIGNERS"	"A"]
             ["04_SKU_EXTERNAL_1_1"	"A"]
             ["04_SKU_EXTERNAL_3_4"	"A"]
             ["04_SKU_INTERNAL_1_1"	"A"]
             ["04_SKU_INTERNAL_3_4"	"A"]
             ["04_SKU_VIDEO_SHORTS"	"A"]
             ["01_MAIL"	"C" "05_COLLECTIONS_ADV"]
             ["02_NEWS"	"C" "05_COLLECTIONS_ADV"]
             ["03_SMM"	"C" "05_COLLECTIONS_ADV"]
             ["05_COLLECTIONS_BANNERS" "C"]
             ["05_COLLECTIONS_VIDEO"	"C"]
             ["05_COLLECTIONS_WEB_BANNERS" "C"]])


  (defn check-prod [art]
    (if (re-matches #"\d{3}" (subs art 0 3))
      false
      true))

  (defn create-path-art
    "Создание пути для сохранения, первые 3, 5 или весь артикул"
    ([art type]
     (let [art-len (count art)]
       (str (subs art 0 (min 3 art-len)) '/
            (subs art 0 (min 5 art-len)) '/
            (when (= type "A")
              (str art '/))))))


  (create-path-art "CL123456" "C")

  (def prod-list (filter check-prod (get-all-articles)))
  (def plafons (filter (complement check-prod) (get-all-articles)))

  (def in (filter #(str/starts-with? % "IN") prod-list))
  (def cl (filter #(str/starts-with? % "CL") prod-list))
  (def el (filter #(str/starts-with? % "EL") prod-list))

  (doseq [d dirs]
    (doseq [a plafons]
      (fs/create-dirs (str "/home/li/TEMP/ACCESSORIES/" (when (= (count d) 3) (last d)) "/"
                           (first d) "/"
                           (create-path-art a (second d))))))

  (doseq [d dirs]
    (doseq [a el]
      (fs/create-dirs (str "/home/li/TEMP/ELETTO/" (when (= (count d) 3) (last d)) "/"
                           (first d) "/"
                           (create-path-art a (second d))))))

  (doseq [d dirs]
    (doseq [a in]
      (fs/create-dirs (str "/home/li/TEMP/INLUX/" (when (= (count d) 3) (last d)) "/"
                           (first d) "/"
                           (create-path-art a (second d))))))

  (doseq [d dirs]
    (doseq [a cl]
      (fs/create-dirs (str "/home/li/TEMP/CITILUX/" (when (= (count d) 3) (last d)) "/"
                           (first d) "/"
                           (create-path-art a (second d))))))




  )
             
             


