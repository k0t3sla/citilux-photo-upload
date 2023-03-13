(ns citilux-photo-upload.core
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [config.core :refer [env]]
            [citilux-photo-upload.upload :refer [upload-fotos]]
            [citilux-photo-upload.utils :refer [notify
                                                get-article
                                                create-path
                                                send-message]])
  (:import [java.io BufferedReader InputStreamReader])
  (:gen-class))

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
    (mapv :art (->> rdr
                    line-seq
                    (map parse-line-1c)))))

(defn move-and-compress [file args]
  (let [_ (sh/sh ".\\node_modules\\.bin\\squoosh-cli.cmd" "--mozjpeg" (:quality env) "-d" "tmp" file)
        orig-size (fs/size file)
        zipped (fs/size (str "tmp\\" (fs/file-name file)))
        ratio (float (/ zipped orig-size))
        name (fs/file-name file)
        art (get-article name)
        path (str (create-path art) name)]
    (println ratio)
    (if (< ratio 0.9)
      (doseq [arg args]
        (fs/create-dirs (str arg (create-path art)))
        (println "comprassing and moving")
        (println (str arg path))
        (fs/copy (str "tmp\\" (fs/file-name file)) (str arg path) {:replace-existing true}))
      (doseq [arg args]
        (fs/create-dirs (str arg (create-path art)))
        (println "just moving")
        (println (str arg path))
        (fs/copy file (str arg path) {:replace-existing true})))
    (fs/delete file)
    (when (fs/exists? (str "tmp\\" (fs/file-name file)))
      (fs/delete (str "tmp\\" (fs/file-name file))))))

(defn copy-file [file args]
  (let [name (fs/file-name file)
        art (get-article name)
        path (str (create-path art) name)]
    (doseq [arg args]
      (fs/create-dirs (str arg (create-path art)))
      (fs/copy file (str arg path) {:replace-existing true}))))

(defn compress-video
  "сжимаем видео для вайлдбериз"
  [files]
  (doseq [file files]
    (fs/create-dirs (str (:out-wb env) (create-path (get-article file))))
    (let [size (fs/size file)]
      (cond
        (> 20971520 size) (copy-file file [(:out-wb env)])
        (> 52428800 size) (do (sh/sh "ffmpeg" "-y" "-i" file "-threads" "1" "-fs" "18M" "-r" "30" "-vf" "scale=1080:1920" (str (:out-wb env) (create-path (get-article file)) (first (fs/split-ext (fs/file-name file))) "_20.mp4"))
                              (copy-file file [(:out-wb env)]))
        :else (do (sh/sh "ffmpeg" "-y" "-i" file "-threads" "1" "-fs" "18M" "-r" "30" "-vf" "scale=1080:1920" (str (:out-wb env) (create-path (get-article file)) (first (fs/split-ext (fs/file-name file))) "_20.mp4"))
                  (sh/sh "ffmpeg" "-y" "-i" file "-threads" "1" "-fs" "47M" "-vf" "scale=1080:1920" (str (:out-wb env) (create-path (get-article file)) (first (fs/split-ext (fs/file-name file))))))))))

(defn filter-files [err? list all-articles]
  (let [out (for [file list]
              (if err?
                (when (some #{(get-article file)} all-articles)
                  file)
                (when-not (some #{(get-article file)} all-articles)
                  file)))]
    (remove nil? out)))

(defn -main
  []
  (try
    (let [all-articles (get-articles-1c)
          err-files (filter-files false (concat (mapv str (fs/glob (:hot-dir env) "**{.mp4,png,psd,jpg,jpeg}"))
                                                (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg}"))) all-articles)
          videos (filter-files true (mapv str (fs/glob (:hot-dir env) "**{.mp4}")) all-articles)
          other-hot-dir (mapv str (fs/glob (:hot-dir env) "**{.png,psd}"))
          jpg-hot-dir (mapv str (fs/glob (:hot-dir env) "**{.jpg,jpeg}"))
          to-upload (set (filter-files true (map get-article jpg-hot-dir) all-articles))
          hot-dir (filter-files true (concat jpg-hot-dir videos other-hot-dir) all-articles)
          hot-dir-wb (filter-files true (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg}")) all-articles)]

      (compress-video videos)

      (when (not-empty hot-dir-wb)
        (doseq [file hot-dir-wb]
          (move-and-compress file [(:out-wb env)])))

      (when (not-empty hot-dir)
        (doseq [file hot-dir]
          (move-and-compress file [(:out-web+1c env) (:out-source env)])))

      (when (not-empty err-files)
        (send-message (str "ошибки в названиях фото" (mapv fs/file-name err-files))))
      
      (if (not-empty to-upload)
          (do 
            (doseq [art to-upload]
                (try
                  (upload-fotos art)
                  (println (str "upload " art " to server"))
                  (catch Exception e (send-message (str "upload on server caught exception: " (.getMessage e))))))
              (notify hot-dir))
          (do (send-message "Новые фотографии отсутствуют")
              (when (not-empty videos)
                (notify hot-dir)))))

    (catch Exception e
      (send-message (str "caught exception: " (.getMessage e)))
      (println (str "caught exception: " (.getMessage e))))))
