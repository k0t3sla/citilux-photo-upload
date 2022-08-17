(ns citilux-photo-upload.core
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [clj-time.core :as t]
            [clj-time.local :as l]
            [config.core :refer [env]]
            [clojure.core.async :refer [go-loop <! timeout]]
            [citilux-photo-upload.upload :refer [upload-fotos]]
            [citilux-photo-upload.utils :refer [notify
                                                get-article
                                                create-path
                                                list-files
                                                send-message]])
  (:import [java.io BufferedReader InputStreamReader])
  (:gen-class))

;; atoms
(def to-upload "очередь товаров для загрузки" (atom #{}))
(def new-fotos "Список добавленных фото" (atom #{}))

(def schedule
  "Атом с временем последнего добавления товара
   для задержки отправления сообщений"
  (atom (l/local-now)))

(def articles "Артикула из 1с" (atom []))
;;atoms

(defn chck-art
  "Проверка соответсвия артикула фотографий с артикулами в базе"
  [art]
  (if (some true? (for [x @articles]
                    (= art (:art x))))
    true
    art))

(defn parse-line-1c
  "Парсим csv построчтно выкидывая все кроме артикула"
  [line]
  (let [[_ art _ _ _ _]
        (re-matches #"(.*);(.*);(.*);(.*);(.*)" line)]
    {:art art}))

(defn get-articles-1c
  "Получаем все артикула в виде мапы"
  []
  (with-open [rdr (-> (io/input-stream (:articles env))
                      (InputStreamReader. "windows-1251")
                      (BufferedReader.))]
    (reset! articles (->> rdr
                          line-seq
                          (mapv parse-line-1c)))))
(go-loop []
  ;; 43200000
  ;; обновляем артикула 2 раза в день 
  (try (get-articles-1c)
       (catch Exception e
         (println (send-message (str "caught exception: Articles file:" (.getMessage e))))
         (send-message (str "caught exception: Articles file:" (.getMessage e)))))
  (<! (timeout 10000))
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
          (send-message (str "не верное название файла в папке - " file))
          (io/delete-file file)))))

(defn watch-dirs []
  (let [hot-dir-wb (list-files (:hot-dir-wb env) "jpg,jpeg")
        hot-dir (list-files (:hot-dir env) "jpg,jpeg,png,mp4")]
    (when-not (empty? hot-dir-wb)
      (try
        (doseq [file hot-dir-wb]
          (copy-file file [(:out-wb env)] (:eror-hot-dir env)))
        (catch Exception e (send-message (str "caught exception: HOT DIR-WB:" (.getMessage e))))))
    (when-not (empty? hot-dir)
      (try
        (doseq [file hot-dir]
          (copy-file file [(:out-web+1c env) (:out-source env)] (:eror-hot-dir env))
          ;; добавляем артикула в очередь на выгрузку и уведомление
          (add-art-to-queue file))
        (catch Exception e (send-message (str "caught exception: HOT DIR:" (.getMessage e))))))))


#_(defn coping-shedule []
    (let [hot-dir-wb (list-files (:hot-dir-wb env) "jpg,jpeg")
          hot-dir (list-files (:hot-dir env) "jpg,jpeg,png,mp4")
          comb (concat hot-dir-wb hot-dir)]))

(defn -main
  []
  (println "Start watching")
  (loop []
    ;; вечный цикл в котором проверяется таймаут для отправки сообщения, 
    ;; копирование файлов в нужные дирректории и отсылка фото на сайт
    (Thread/sleep 1000)
    (try
      (watch-dirs)
      (catch Exception e (println (str "caught exception: " (.getMessage e)))))
    (when (and (not-empty @to-upload)
               (check-timeout @schedule))
      ;; здесь отправка всего и вся 
      (doseq [art @to-upload]
        (reset! schedule (l/local-now))
        (try
          (upload-fotos art)
          (catch Exception e (send-message (str "caught exception: " (.getMessage e))))))
      (reset! to-upload #{})
      (notify @new-fotos)
      (reset! new-fotos #{})
      (reset! schedule (l/local-now)))
    (recur)))