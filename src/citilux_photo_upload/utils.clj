(ns citilux-photo-upload.utils
  (:require [babashka.fs :as fs]
            [clj-http.client :as client]
            [cheshire.core :refer [generate-string]]
            [config.core :refer [env]]
            [clojure.java.shell :as sh] 
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

(defn parse-resp [resp]
  (str/split (apply str (->> resp
                             (drop 4)
                             (drop-last 4))) #"\\\",\\\""))

(defn get-article
  "Get the article code before the first underscore"
  [file]
  (first (str/split (fs/file-name file) #"_")))

(defn create-path
  "Создание пути для сохранения, первые 3, 5 или весь артикул"
  ([file-path]
   (let [file-name (fs/file-name file-path)
         adv (cond 
               (str/includes? file-name "_SMM_") "05_COLLECTIONS_ADV/03_SMM/"
               (str/includes? file-name "_BANNERS_") "05_COLLECTIONS_BANNERS/"
               (str/includes? file-name "_WEBBANNERS_") "05_COLLECTIONS_WEB_BANNERS/"
               (str/includes? file-name "_NEWS_") "05_COLLECTIONS_ADV/02_NEWS/"
               (str/includes? file-name "_MAIL_") "05_COLLECTIONS_ADV/01_MAIL/")
         all (when (str/includes? file-name "_ALL_") true)
         art (get-article file-name)
         art-len (count art)
         first-2 (subs art 0 2)
         brand (cond
                 (= first-2 "CL") "20_CITILUX"
                 (= first-2 "EL") "50_ELETTO"
                 (= first-2 "IN") "40_INLUX"
                 (re-matches #"\d{2}.*" first-2) "10_ACCESSORIES")
         out-path (str
                   (:out-dir env)
                   brand '/
                   (when adv adv)
                   (subs art 0 (min 3 art-len)) '/
                   (subs art 0 (min 5 art-len)) '/
                   (when-not all (str art '/)))]
     out-path)))

(defn create-path-with-root
  "Создание пути для сохранения, первые 3, 5 или весь артикул"
  ([file-path dir-to-save]
   (let [art (get-article (fs/file-name file-path))
         art-len (count art)
         first-2 (subs art 0 2)
         brand (cond
                 (= first-2 "CL") "20_CITILUX"
                 (= first-2 "EL") "50_ELETTO"
                 (= first-2 "IN") "40_INLUX"
                 (re-matches #"\d{2}.*" first-2) "10_ACCESSORIES")
         out-path (str (:out-dir env)
                       brand '/
                       dir-to-save
                       (subs art 0 (min 3 art-len)) '/
                       (subs art 0 (min 5 art-len)) '/
                       art '/)]
     out-path)))


(defn get-dimm [^String path]
  (let [img (img/load-image path)
        ^int w (try
                 (img/width img)
                 (catch Exception e (.getMessage e)))
        ^int h (try
                 (img/height img)
                 (catch Exception e (.getMessage e)))]
    (if (and (= w 2000) (= h 2000))
      "1x1"
      "3x4")))

(defn create-path-dimm
  "Создание пути для сохранения, первые 3, 5 или весь артикул"
  ([file]
   (let [art-len (count file)
         art (get-article file)
         first-2 (subs art 0 2)
         dimm (get-dimm file)
         brand (cond
                 (= first-2 "CL") "20_CITILUX"
                 (= first-2 "EL") "50_ELETTO"
                 (= first-2 "IN") "40_INLUX"
                 (re-matches #"\d{2}.*" first-2) "10_ACCESSORIES")
         out-path (str brand '/
                       (if (= dimm "1x1")
                         "04_SKU_INTERNAL_1_1/"
                         "04_SKU_INTERNAL_3_4/")
                       (subs art 0 (min 3 art-len)) '/
                       (subs art 0 (min 5 art-len)) '/
                       art '/)]
     out-path)))

(defn create-path-dimm-source
  "Создание пути для сохранения, первые 3, 5 или весь артикул"
  ([file]
   (let [art-len (count file)
         art (get-article file)
         first-2 (subs art 0 2)
         dimm (get-dimm file)
         brand (cond
                 (= first-2 "CL") "20_CITILUX"
                 (= first-2 "EL") "50_ELETTO"
                 (= first-2 "IN") "40_INLUX"
                 (re-matches #"\d{2}.*" first-2) "10_ACCESSORIES")
         out-path (str
                   (:out-dir env)
                   brand '/
                   (if (= dimm "1x1")
                     "03_SOURCE_1_1/"
                     "03_SOURCE_3_4/")
                   (subs art 0 (min 3 art-len)) '/
                   (subs art 0 (min 5 art-len)) '/
                   art '/)]
     (fs/create-dirs out-path)
     out-path)))

(defn move-and-compress [file]
  (let [mozjpeg-bin "./cjpeg-static"
        tmp-path (str "tmp" '/ (str (first (fs/split-ext (fs/file-name file))) ".jpg"))
        _ (sh/sh mozjpeg-bin "-quality" (:quality env) "-outfile" tmp-path file)
        orig-size (fs/size file)
        zipped-size (fs/size tmp-path)
        ratio (float (/ zipped-size orig-size))
        path (str (:out-dir env) (create-path-dimm file))]
    (println ratio)
    (if (< ratio 0.9)
      (do
        (fs/create-dirs path)
        (println "comprassing and moving")
        (println path)
        (fs/copy tmp-path path {:replace-existing true}))
      (do
        (fs/create-dirs path)
        (println "just moving")
        (println path)
        (fs/copy file path {:replace-existing true})))
    (fs/delete-if-exists file)
    (fs/delete-if-exists tmp-path)))

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

(defn notify-msg-create
  "Перечисляем фото или видео с их колличеством"
  [{:keys [files heading err?]}]
  (let [file-types ["jpg" "mp4" "psd" "png"]
        msgs (for [file-type file-types]
               (process-files files file-type heading err?))
        msg (str (when heading heading) (apply str msgs))]
    (println msg)
    (when-not (str/blank? msg)
      msg)))

(defn get-all-articles []
  (let [resp (-> (client/post (str (:root-1c-endpoint env) (:get-all-articles-url env))
                              {:headers {"Authorization" (:token-1c env)}})
                 :body
                 parse-resp)]
    (filterv (complement #(or
                           (str/includes? % "_DRV")
                           (str/includes? % "МЛК")
                           (str/includes? % "ТЕСТ")
                           (str/includes? % "pult")
                           (str/includes? % "_(GroupPack)")
                           (str/starts-with? % "BOX_"))) resp)))



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
    (or (and (= w 2000) (or (= h 2000) (= h 2667)))
        false)))

(defn count-files-with-extension [file-list]
  (->> file-list
       (mapv fs/extension)
       frequencies))

(defn get-stat-files [dir]
  (let [oz-path (str (:design env) dir)
        files (mapv str (fs/glob oz-path "**{.jpg,jpeg,png}"))
        file-groups (group-by get-article files)
        data (mapv (fn [[k v]] [k (count-files-with-extension v)]) file-groups)]
    (walk/keywordize-keys (into {} data))))

(defn general-stat-handler []
  {:WB (get-stat-files "WB")
   :OZ (get-stat-files "OZON")})

(defn article-stat-handler [art]
  {:WB ((keyword art) (get-stat-files "WB"))
   :OZ ((keyword art) (get-stat-files "OZON"))})

(defn exist? [file articles]
  (some #(= (get-article file) %) articles))

(defn copy-file 
  "on input filepath"
  [^String file]
  (let [name (fs/file-name file)
        art (get-article name)]
    (fs/create-dirs (create-path art))
    (fs/copy file (create-path art) {:replace-existing true})))

(defn copy-abris
  "on input filepath"
  [^String file]
  (let [file-name (fs/file-name file)
        art (get-article file-name)
        path-to-input (str (:hot-dir env) file-name)
        path-todir (str (:out-dir env) (create-path-dimm path-to-input))
        path-todir-source (create-path-dimm-source path-to-input)
        path-todir-abris (create-path-with-root art "01_PRODUCTION_FILES/01_ABRIS/")
        _ (fs/create-dirs path-todir)
        _ (fs/create-dirs path-todir-abris)
        _ (fs/create-dirs path-todir-source)
        _ (println "path-todir " path-todir)
        _ (println "path-todir-abris " path-todir-abris)
        _ (println "path-todir-source " path-todir-source)
        out-internal (str path-todir file-name)
        out-source (str path-todir-source file-name)
        out-abris (str path-todir-abris file-name)]
    (if (fs/exists? out-internal)
      (do
        (println "file exist")
        (fs/copy file out-source {:replace-existing true})
        (println "copied" file "to" out-source)
        (move-and-compress file))
      (do
        (println "file not  exist")
        (fs/copy file out-internal {:replace-existing true})
        (fs/copy file out-abris {:replace-existing false})))
    (fs/delete-if-exists file)))

(comment
  (copy-abris "/home/li/TEMP/HOT_DIR/CL237B310_31.jpg")
  )

(defn move-file
  "on input filepath"
  [^String file]
  (let [file-name (fs/file-name file)
        path (str (create-path file) file-name)]
    (fs/create-dirs (create-path file-name))
    (fs/copy file path {:replace-existing true})
    (fs/delete-if-exists file)))


(defn split-articles [^String s]
  (filter #(not (str/blank? %)) (str/split s #",|\n|\t")))

(defn create-dir-if-not-exist [path]
  (when (not (fs/exists? path))
    (fs/create-dirs path)))


(defn create-dirs-ctructure []

  (let [all-articles (take 3588 (get-all-articles))
        check-prod (fn [art] (if (re-matches #"\d{3}" (subs art 0 3))
                               false
                               true))

        create-path-art (fn [art type]
                          (let [art-len (count art)]
                            (str (subs art 0 (min 3 art-len)) '/
                                 (subs art 0 (min 5 art-len)) '/
                                 (when (= type "A")
                                   (str art '/)))))

        dirs [["0_TEST_REPORTS" "C" "01_PRODUCTION_FILES"]
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
              ["05_COLLECTIONS_WEB_BANNERS" "C"]]

        prod-list (filter check-prod all-articles)
        accessories (filter (complement check-prod) all-articles)

        in (filter #(str/starts-with? % "IN") prod-list)
        cl (filter #(str/starts-with? % "CL") prod-list)
        el (filter #(str/starts-with? % "EL") prod-list)

        create-path-dir (fn [d a root]
                          (str (:out-dir env) root (when (= (count d) 3) (last d)) "/"
                               (first d) "/"
                               (create-path-art a (second d))))]

    
    (doseq [d dirs]
      (doseq [a accessories]
        (create-dir-if-not-exist
         (create-path-dir d a "ACCESSORIES/"))))

    (doseq [d dirs]
      (doseq [a el]
        (create-dir-if-not-exist
         (create-path-dir d a "ELETTO/"))))

    (doseq [d dirs]
      (doseq [a in]
        (create-dir-if-not-exist
         (create-path-dir d a "INLUX/"))))

    (doseq [d dirs]
      (doseq [a cl]
        (create-dir-if-not-exist
         (create-path-dir d a "CITILUX/"))))))
        

(comment
  
  (count (get-all-articles))

  ;;;; DIR GENERATOR

  (take 15 (reverse (get-all-articles)))

  (count (get-all-articles))

  (take 1 [1 2])

  (create-dirs-ctructure)


  ()

  )


