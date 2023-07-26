(ns citilux-photo-upload.core
  (:require [babashka.fs :as fs]
            [clojure.string :as string]
            [clojure.java.shell :as sh]
            [config.core :refer [env]]
            [citilux-photo-upload.upload :refer [upload-fotos]]
            [citilux-photo-upload.utils :refer [notify
                                                delimiter
                                                get-article
                                                create-path
                                                send-message
                                                create-art-link
                                                get-all-articles]])
  (:gen-class))

(defn move-and-compress [file args]
  (let [path-to-sqoosh (str "." delimiter "node_modules" delimiter ".bin" delimiter (if (fs/windows?)
                                                                                      "squoosh-cli.cmd"
                                                                                      "squoosh-cli"))
        _ (sh/sh path-to-sqoosh "--mozjpeg" (:quality env) "-d" "tmp" file)
        orig-size (fs/size file)
        tmp-path (str "tmp" delimiter (str (first (fs/split-ext (fs/file-name file))) ".jpg"))
        zipped-size (fs/size tmp-path)
        ratio (float (/ zipped-size orig-size))
        name (str (first (fs/split-ext (fs/file-name file))) ".jpg")
        art (get-article name)
        path (str (create-path art) name)]
    (println ratio)
    (if (< ratio 0.9)
      (doseq [arg args]
        (fs/create-dirs (str arg (create-path art)))
        (println "comprassing and moving")
        (println (str arg path))
        (fs/copy tmp-path (str arg path) {:replace-existing true}))
      (doseq [arg args]
        (fs/create-dirs (str arg (create-path art)))
        (println "just moving")
        (println (str arg path))
        (fs/copy file (str arg path) {:replace-existing true})))
    (fs/delete-if-exists file)
    (fs/delete-if-exists tmp-path)))

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

(defn filter-files [err? list all-articles]
  (let [out (for [file list]
              (if err?
                (when (some #{(get-article file)} all-articles)
                  file)
                (when-not (some #{(get-article file)} all-articles)
                  file)))]
    (remove nil? out)))

(defn upload-from-file [art-to-upload all-articles]
  (let [err-arts (vec (filter-files false art-to-upload all-articles))
        correct-arts (vec (filter-files true art-to-upload all-articles))
        files (for [art correct-arts]
                (map str (fs/glob (str (:out-web+1c env) (create-path art)) "**{.jpeg,jpg}")))]
    (doseq [art correct-arts]
      (try
        (println (str "upload " art " to server"))
        (upload-fotos (str art))
        (catch Exception e (send-message (str "upload on server caught exception: " (.getMessage e))))))
    (when (not-empty files)
      (notify (flatten files)))
    (when (not-empty err-arts) (send-message (str "На сайт не загружены из за ошибки артикула:\n" (apply str (for [art err-arts] 
                                                                                                               (str art "\n"))))))))

(defn -main
  []
  (try
    (let [all-articles (get-all-articles)
          err-files (filter-files false (concat (mapv str (fs/glob (:hot-dir env) "**{.mp4,png,psd,jpg,jpeg}"))
                                                (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg,png,mp4}"))) all-articles)
          videos (filter-files true (mapv str (fs/glob (:hot-dir env) "**{.mp4}")) all-articles)
          videos_wb (filter-files true (mapv str (fs/glob (:hot-dir-wb env) "**{.mp4}")) all-articles)
          all_videos (concat videos videos_wb)
          foto-hot-dir (mapv str (fs/glob (:hot-dir env) "**{.jpg,jpeg,png}"))
          to-upload (set (filter-files true (map get-article foto-hot-dir) all-articles))
          hot-dir-other (filter-files true (mapv str (fs/glob (:hot-dir env) "**{psd}")) all-articles)
          wb-90 (filter-files true (mapv str (fs/glob (:wb-90-hot-dir env) "**{.jpg,jpeg,png}")) all-articles)
          hot-dir (filter-files true foto-hot-dir all-articles)
          hot-dir-wb (filter-files true (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg,png}")) all-articles)
          file-to-upload (string/split-lines (slurp "to-upload.txt"))]

      (if (not= file-to-upload [""])
        ;; if file not empty just uploading
        (upload-from-file file-to-upload all-articles)
        ;; else working with hot-dir
        (do

          (when (not-empty all_videos)
            
            (when (not-empty videos)
              (doseq [file videos]
                (move-file file [(:out-source env)])))

            (when (not-empty videos_wb)
              (doseq [file videos_wb]
                (move-file file [(:out-wb env)])))
            
            (notify all_videos))

          (when (not-empty hot-dir-wb)
            (doseq [file hot-dir-wb]
              (copy-file file [(:out-source-wb env)])
              (move-and-compress file [(:out-wb env)])))

          (when (not-empty hot-dir)
            (doseq [file hot-dir]
              (copy-file file [(:out-source env)])
              (move-and-compress file [(:out-web+1c env)])))

          (when (not-empty wb-90)
            (doseq [file wb-90]
              (copy-file file [(:out-wb-90-source env)])
              (move-and-compress file [(:out-wb-90 env)])))

          (when (not-empty hot-dir-other)
            (doseq [file hot-dir-other]
              (move-file file [(:out-web+1c env) (:out-source env)])))

          (when (not-empty err-files)
            (send-message (str "ошибки в названиях фото" (mapv fs/file-name err-files))))

          (if (not-empty (concat to-upload hot-dir-wb))
            (do
              (doseq [art to-upload]
                (try
                  (upload-fotos art)
                  (println (str "upload " art " to server"))
                  (catch Exception e (send-message (str "upload on server caught exception: " (.getMessage e))))))
              (when (not-empty hot-dir) (notify hot-dir))
              (when (not-empty hot-dir-wb) (notify hot-dir-wb true)))
            (send-message "Новые фотографии отсутствуют")))))

    (catch Exception e
      (send-message (str "caught exception: " (.getMessage e)))
      (println (str "caught exception: " (.getMessage e))))))
