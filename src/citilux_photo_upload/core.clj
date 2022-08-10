(ns citilux-photo-upload.core
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [cheshire.core :as ch]
            [clj-time.core :as t]
            [clj-time.local :as l]
            [clojure-watch.core :refer [start-watch]]
            [clojure.core.async :refer [go-loop <! timeout]]
            [clj-http.client :as client]
            [config.core :refer [env]]
            [telegrambot-lib.core :as tbot]) 
  (:import [java.io FileReader BufferedReader PushbackReader FileInputStream File FileOutputStream InputStreamReader PrintWriter OutputStreamWriter]
           [java.util Properties]
           [jcifs.smb SmbFileInputStream SmbFile SmbFileOutputStream]
           [jcifs Config])
  (:gen-class))

(def prop (Properties.)) 
(.setProperty prop "jcifs.smb.client.username", "")
(.setProperty prop "jcifs.smb.client.password", "")
(Config/setProperties prop)

(def mybot (tbot/create (:tbot env)))

;; atoms
(def to-upload "очередь товаров для загрузки" (atom #{}))
(def new-fotos "Список добавленных фото" (atom []))

(def schedule
  "Атом с временем последнего добавления товара
   для задержки отправления сообщений"
  (atom (l/local-now)))

(def articles "Артикула из 1с" (atom []))

(defn parse-line-1c 
  "Парсим csv построчтно выкидывая все кроме артикула" 
  [line] 
  (let [[_ art _ _ _ _]
        (re-matches #"(.*);(.*);(.*);(.*);(.*)" line)]
    {:art art}))

(defn list-files 
  "Список файлов с нужным расширением" 
  [path ext] 
  (let [grammar-matcher (.getPathMatcher
                         (java.nio.file.FileSystems/getDefault)
                         (str "glob:*.{" ext "}"))]
    (->> path
         io/file
         file-seq
         (filter #(.isFile ^java.io.File %))
         (filter #(.matches grammar-matcher (.getFileName ^sun.nio.fs.UnixPath (.toPath ^java.io.File %))))
         (mapv #(.getAbsolutePath ^java.io.File %)))))

(defn send-message [message]
  (tbot/call mybot "sendMessage" {:chat_id (:chat_id env) :text message}))

(defn get-articles-1c
 "Получаем все артикула в виде мапы" 
  []
  (with-open [rdr (->
                   #_(SmbFileInputStream. (:articles env))
                   (SmbFileInputStream. "Smb://cl-s2/common/exchange/www/items.csv")
                   (InputStreamReader. "windows-1251")
                   (BufferedReader.))]
    (reset! articles (->> rdr
                          line-seq
                          (mapv parse-line-1c)))))

(defn chck-art 
 "Проверка соответсвия артикула фотографий с артикулами в базе" 
  [art]
  (if (some true? (for [x @articles]
                    (= art (:art x))))
    true
    art))

(defn get-article
  "Тримаем, и получаем артикула без нижних подчеркиваний"
  [file]
  (str/trim (first (str/split (fs/name file) #"_(?!.*_)"))))

(defn filter-files [files ext]
  (filter (fn [x]
            (= (fs/extension x) ext))
          files))

(defn notify
  "Перечисляем фото или видео с их колличеством"
  [files]
  (let [jpg (vec (filter-files files ".jpg"))
        mp4 (vec (filter-files files ".mp4"))
        freq-jpg (into [] (frequencies (map get-article jpg)))
        freq-mp4 (into [] (frequencies (map get-article mp4)))
        jpg-out (for [v freq-jpg]
                  (str (str/join " - " v) " шт\n"))
        mp4-out (for [v freq-mp4]
                  (str (str/join " - " v) " шт\n"))
        msg (str (when (not-empty jpg-out) (str "Добавлены новые фото по следующим позициям - \n" (apply str jpg-out))) "\n"
                 (when (not-empty mp4-out) (str "Добавлены новые видео по следующим позициям - \n" (apply str mp4-out))))]
    (println msg)
    (send-message msg)))

(defn create-path
  "Созание пути для сохранения, первые 3 ,5 весь артикул"
  [art]
  (str (subs art 0 3) "/" (subs art 0 5) "/" art "/"))

(go-loop []
  ;; обновляем артикула 2 раза в день
  (try (get-articles-1c)
       (catch Exception e 
         (println (send-message (str "caught exception: " (.getMessage e))))
         (send-message (str "caught exception: " (.getMessage e)))))
  (<! (timeout 43200000))
  (recur))

(defn add-art-to-queue [file]
  (let [name (fs/name file)
        art (get-article name)]
    (when (true? (chck-art art))
      (swap! to-upload conj art)
      (swap! new-fotos conj file)
      (reset! schedule (l/local-now)))))

(defn check-timeout [time]
  (boolean (t/after? (l/local-now) (t/plus time (t/minutes (:timeout env))))))

(defn copy-file [file args eror]
  (let [name (fs/name file)
        art (get-article name)
        path (str (create-path (get-article art)) (fs/base-name file))]
    (if (true? (chck-art art))
      (do (doseq [arg args]
            (fs/copy+ file (str arg path)))
          (io/delete-file file))
      (do (fs/copy+ file (str eror (fs/base-name file)))
          (io/delete-file file)
          (send-message (str "не верное название файла в папке HOT DIR - " name))))))

(defn encode64 [path]
  (.encodeToString
   (java.util.Base64/getEncoder)
   (org.apache.commons.io.FileUtils/readFileToByteArray
    (io/file path))))

(defn upload-fotos
  "Needs to be implemented"
  [art]
  (let [files (sort (list-files (str (:out-web+1c env)
                                     (create-path art)) "jpeg,jpg"))
        detail-foto (when (> (count files) 0)
                      (encode64 (first files)))
        encoded-fotos (when (> (count files) 1)
                        (mapv encode64 (drop 1 files)))
        data {:art art
              :detail detail-foto
              :photos encoded-fotos}
        resp (try
               (client/get (:url env)
                         {:basic-auth [(:login env) (:password env)]
                          :body (ch/generate-string data)
                          :content-type :json
                          :conn-timeout 300000})
               (catch Exception e (send-message (str "caught exception: " (.getMessage e)))))]
    (when (not= (:status resp) 200)
      (send-message (str "проблемы при загрузке фотографий - " art)))))

(defn -main
  []
  (start-watch [{:path (:hot-dir env)
                 :event-types [:create]
                 :bootstrap (fn [path] (println "Starting to watch " path))
                 :callback (fn [_ file]
                             (try
                               (copy-file file [(:out-web+1c env) (:out-source env)] (:eror-hot-dir env))
                               (add-art-to-queue file)
                               (catch Exception e (send-message (str "caught exception: " (.getMessage e))))))
                 :options {:recursive false}}])
  (start-watch [{:path (:hot-dir-wb env)
                 :event-types [:create]
                 :bootstrap (fn [path] (println "Starting to watch " path))
                 :callback (fn [_ file]
                             (try
                               (copy-file file [(:out-wb env)] (:eror-hot-dir-wb env))
                               (catch Exception e (send-message (str "caught exception: " (.getMessage e))))))
                 :options {:recursive false}}])
  (loop []
    ;; вечный цикл в котором проверяется таймаут для отправки сообщения, 
    ;; копирование файлов в нужные дирректории и отсылка фото на сайт
    (Thread/sleep 1000)
    (when (and (not-empty @to-upload)
               (check-timeout @schedule))
      ;; todo здесь отправка всего и вся
      (let [articles @to-upload]
        (reset! to-upload #{})
        (doseq [art articles]
          (reset! schedule (l/local-now))
          (try
            (upload-fotos art)
            (catch Exception e (send-message (str "caught exception: " (.getMessage e))))))
        
        (notify @new-fotos)
        (reset! new-fotos [])
        (reset! schedule (l/local-now))))
    (recur)))