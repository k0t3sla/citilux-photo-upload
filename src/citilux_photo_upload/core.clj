(ns citilux-photo-upload.core
  (:require [babashka.fs :as fs]
            [clojure.string :as string]
            [clojure.java.shell :as sh]
            [config.core :refer [env]]
            [org.httpkit.server :as http]
            [hiccup.page :as hiccup]
            [schejulure.core :as cron]
            [reitit.ring :as ring]
            [clojure-watch.core :refer [start-watch]]
            [clojure.java.io :as io]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.util.response :as response]
            [citilux-photo-upload.upload :refer [upload-fotos]]
            [citilux-photo-upload.utils :refer [notify
                                                get-dimm
                                                delimiter
                                                get-article
                                                create-path
                                                send-message
                                                get-all-articles]])
  (:gen-class))

(set! *warn-on-reflection* true)

(def all-articles (atom []))
(def files (atom []))
(defn update-articles []
  (println "updating articles")
  (reset! all-articles (get-all-articles)))

(defn move-and-compress [file args]
  (let [mozjpeg-bin (if (fs/windows?)
                      "./cjpeg-static.exe"
                      "./cjpeg-static")
        tmp-path (str "tmp" delimiter (str (first (fs/split-ext (fs/file-name file))) ".jpg"))
        _ (sh/sh mozjpeg-bin "-quality" (:quality env) "-outfile" tmp-path file)
        orig-size (fs/size file)
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

(defn filter-files [{:keys [filter-errors? include-in? files]}]
  (let [filtered (filter some?
                         (for [file files]
                           (cond
                             filter-errors? (when-not (some #{(get-article file)} @all-articles) file)
                             :else (when (some #{(get-article file)} @all-articles) file))))]
    (if include-in?
      (filter (fn [s] (string/starts-with? (get-article s) "IN")) filtered)
      (filter (fn [s] (not (string/starts-with? (get-article s) "IN"))) filtered))))

(defn upload-from-file [art-to-upload]
  (let [err-arts (vec (filter-files {:filter-errors? true :files art-to-upload}))
        correct-arts (vec (filter-files {:files art-to-upload}))
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

(defn get-files []
  (let [err-files-inlux (filter-files {:filter-errors? true :include-in? true
                                       :files (concat (mapv str (fs/glob (:hot-dir env) "**{.mp4,png,psd,jpg,jpeg}"))
                                                      (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg,png,mp4}")))})
        err-files (filter-files  {:filter-errors? true
                                  :files (concat (mapv str (fs/glob (:hot-dir env) "**{.mp4,png,psd,jpg,jpeg}"))
                                                 (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg,png,mp4}")))})
        videos (filter-files {:files (mapv str (fs/glob (:hot-dir env) "**{.mp4}"))})
        videos_wb (filter-files {:files (mapv str (fs/glob (:hot-dir-wb env) "**{.mp4}"))})
        videos-inlux (filter-files {:include-in? true :files (mapv str (fs/glob (:hot-dir env) "**{.mp4}"))})
        videos-inlux_wb (filter-files {:include-in? true :files (mapv str (fs/glob (:hot-dir-wb env) "**{.mp4}"))})
        foto-hot-dir (mapv str (fs/glob (:hot-dir env) "**{.jpg,jpeg,png}"))
        to-upload (set (filter-files {:files (map get-article foto-hot-dir)}))
        hot-dir-other (filter-files {:files (mapv str (fs/glob (:hot-dir env) "**{psd}"))})
        wb-90 (filter-files {:files (mapv str (fs/glob (:wb-90-hot-dir env) "**{.jpg,jpeg,png}"))})
        hot-dir (filter get-dimm (filter-files {:files foto-hot-dir}))
        hot-dir-wb (filter get-dimm (filter-files {:files (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg,png}"))}))
        hot-dir-inlux (filter get-dimm (filter-files {:include-in? true
                                                      :files (mapv str (fs/glob (:hot-dir env) "**{.jpg,jpeg,png}"))}))
        hot-dir-wb-inlux (filter get-dimm (filter-files {:include-in? true
                                                         :files (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg,png}"))}))
        not-correct-dimm (filter #(not (get-dimm %)) (concat (mapv str (fs/glob (:hot-dir env) "**{.jpg,jpeg,png}"))
                                                             (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg,png}"))))]
    {:err-files-inlux err-files-inlux
     :err-files err-files
     :videos videos
     :videos_wb videos_wb
     :videos-inlux videos-inlux
     :videos-inlux_wb videos-inlux_wb
     :to-upload to-upload
     :hot-dir-other hot-dir-other
     :wb-90 wb-90
     :hot-dir hot-dir
     :hot-dir-wb hot-dir-wb
     :hot-dir-inlux hot-dir-inlux
     :hot-dir-wb-inlux hot-dir-wb-inlux
     :not-correct-dimm not-correct-dimm}))


(defn update-files []
  (println "updating articles")
  (reset! files (get-files)))

(defn send-files
  []
  (try
    (let [files (get-files)] 
      
      (when (not-empty (concat (:videos files) (:videos_wb files)))
    
        (when (not-empty (:videos files))
          (doseq [file (:videos files)]
            (move-file file [(:out-source env)])))
    
        (when (not-empty (:videos_wb files))
          (doseq [file (:videos_wb files)]
            (move-file file [(:out-wb env)])))
    
        (notify (concat (:videos files) (:videos_wb files)) "В папку WEB+1C_wildberries\n"))
    
      (when (not-empty (concat (:videos-inlux files) (:videos-inlux_wb files)))
    
        (when (not-empty (:videos-inlux files))
          (doseq [file (:videos-inlux files)]
            (move-file file [(:out-source-inlux env)])))
    
        (when (not-empty (:videos-inlux_wb files))
          (doseq [file (:videos-inlux_wb files)]
            (move-file file [(:out-inlux-wb env)])))
    
        (notify (concat (:videos-inlux files) (:videos-inlux_wb files)) "В папку INLUX\n"))
    
      (when (not-empty (:hot-dir-wb files))
        (doseq [file (:hot-dir-wb files)]
          (copy-file file [(:out-source-wb env)])
          (move-and-compress file [(:out-wb env)])))
    
      (when (not-empty (:hot-dir files))
        (doseq [file (:hot-dir files)]
          (copy-file file [(:out-source env)])
          (move-and-compress file [(:out-web+1c env)])))
    
      (when (not-empty (:hot-dir-inlux files))
        (doseq [file (:hot-dir-inlux files)]
          (copy-file file [(:out-source-inlux env)])
          (move-and-compress file [(:out-inlux env)])))
    
      (when (not-empty (:hot-dir-wb-inlux files))
        (doseq [file (:hot-dir-wb-inlux files)]
          (copy-file file [(:out-source-inlux-wb env)])
          (move-and-compress file [(:out-inlux-wb env)])))
    
      (when (not-empty (:wb-90 files))
        (doseq [file (:wb-90 files)]
          (copy-file file [(:out-wb-90-source env)])
          (move-and-compress file [(:out-wb-90 env)])))
    
      (when (not-empty (:hot-dir-other files))
        (doseq [file (:hot-dir-other files)]
          (move-file file [(:out-web+1c env) (:out-source env)])))
    
      (when (not-empty (:err-files files))
        (send-message (str "ошибки в названиях фото" (mapv fs/file-name (:err-files files)))))
    
      (when (not-empty (:err-files-inlux files))
        (send-message (str "ошибки в названиях фото" (mapv fs/file-name (:err-files-inlux files)))))
    
      (when (not-empty (:hot-dir-inlux files)) (notify (:hot-dir-inlux files) "В папку INLUX\n"))
      (when (not-empty (:hot-dir-wb-inlux files)) (notify (:hot-dir-wb-inlux files) "В папку INLUX-WB\n"))
    
      (if (not-empty (concat (:to-upload files) (:hot-dir-wb files) (:hot-dir-inlux files) (:hot-dir-wb-inlux files)))
        (do
          (doseq [art (:to-upload files)]
            (try
              (upload-fotos art)
              (println (str "upload " art " to server"))
              (catch Exception e (send-message (str "upload on server caught exception: " (.getMessage e))))))
          (when (not-empty (:hot-dir files)) (notify (:hot-dir files)))
          (when (not-empty (:hot-dir-wb files)) (notify (:hot-dir-wb files) "В папку WEB+1C_wildberries\n")))
        (send-message "Новые фотографии отсутствуют")))
    
    (catch Exception e
      (send-message (str "caught exception: " (.getMessage e)))
      (println (str "caught exception: " (.getMessage e))))))

(defn form-page [_]
  (let [files @files
        hot-dir-files (concat (:hot-dir files) (:hot-dir-wb files))
        err-files (concat (:err-files-inlux files) (:err-files files))
        not-correct-dimm (:not-correct-dimm files)]
    (hiccup/html5 
     [:body
      [:head (hiccup/include-css "styles.css")]
      [:head (hiccup/include-js "htmx.js")]
      [:h1 "Список товаров"]
      (if (> (count hot-dir-files) 0)
        [:ul
         (for [file hot-dir-files]
           [:li (fs/file-name file)])
         [:form {:method "POST" :action "/hot-dir-upload"}
          [:button {:type "submit"} "Загрузить"]]]
        [:h2 "Фото отсутствуют"])
      (when (> (count err-files) 0)
        [:div
         [:h3 "Файлы с ошибками"]
         [:ul
          (for [file (concat err-files)]
            [:li (fs/file-name file)])]])
      (when (> (count not-correct-dimm) 0)
        [:div
         [:h3 "Файлы с не правильными размерами"]
         [:ul
          (for [file not-correct-dimm]
            [:li (fs/file-name file)])]])
      [:br]
      [:hr]
      [:br]
      [:form {:method "POST" :action "/upload"}
       [:textarea {:rows 10 :cols 45 :name "arts" :required true :placeholder "CL123456, CL234567"}]
       [:button {:type "submit"} "Загрузить на сервер"]]
      [:button {:hx-post "/update" :hx-swap "outerHTML"} "Обновить список"]])))

(defn hotdir-handler [_]
  (try (send-files)
       (hiccup/html5
        [:body
         [:head (hiccup/include-css "styles.css")]
         [:h1 "Фото сжаты, разложены по папкам и отпраленны на сервер"]
         [:a {:href "/"} "Вернутся на главню"]])
       (catch Exception e
         (hiccup/html5
          [:body
           [:head (hiccup/include-css "styles.css")]
           [:h1 "Что то пошло не так"]
           [:h2 e]
           [:a {:href "/"} "Вернутся на главню"]]))))

(defn update-handler [_]
  (try (update-articles)
       "<h2>Успешно обновлено</h2>"
       (catch Exception e
         (str "<h2>Ошибка</h2>" e))))

(defn upload-to-server [request]
  (let [params (-> request
                   :params
                   :arts
                   (string/split #","))
        arts (mapv string/trim params)
        err (filter-files {:filter-errors? true :files arts})
        correct (filter-files {:files arts})]
    (try (upload-from-file correct)
         (hiccup/html5
          [:body
           [:head (hiccup/include-css "styles.css")]
           [:h1 "Фото по этим артикулам отправлены на сервер"]
           [:ul (for [art correct]
                  [:li art])]
           (when err
             [:hr]
             [:ul "Артикула введеные с ошибками"
              (for [e err]
                [:li e])])
           [:a {:href "/"} "Вернутся на главню"]])
         (catch Exception e
           (hiccup/html5
            [:body
             [:head (hiccup/include-css "styles.css")]
             [:h1 "Что то пошло не так"]
             [:h2 e]
             [:a {:href "/"} "Вернутся на главню"]])))))

(def handler
  (ring/ring-handler
   (ring/router
    [["/"
      {:get (fn [request]
              (-> (form-page request)
                  (response/response)
                  (response/header "content-type" "text/html")))}]
     ["/hot-dir-upload"
      {:post (fn [request]
               (-> (hotdir-handler request)
                   (response/response)
                   (response/header "content-type" "text/html")))}]
     ["/upload"
      {:post (fn [request]
               (-> (upload-to-server request)
                   (response/response)
                   (response/header "content-type" "text/html")))}]
     ["/update"
      {:post (fn [request]
               (-> (update-handler request) 
                   (response/response)
                   (response/header "content-type" "text/html")))}]])))

(defmethod response/resource-data :resource
  [^java.net.URL url]
  ;; GraalVM resource scheme
  (let [resource (.openConnection url)
        len (#'ring.util.response/connection-content-length resource)]
    (when (pos? len)
      {:content        (.getInputStream resource)
       :content-length len
       :last-modified  (#'ring.util.response/connection-last-modified resource)})))

(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    ;; graceful shutdown: wait 100ms for existing requests to be finished
    ;; :timeout is optional, when no timeout, stop immediately
    (@server :timeout 100)
    (reset! server nil)))

(defn start-server []
  (println "starting http.kit server http://localhost:8080/")
  (reset! server (http/run-server
                  (wrap-defaults
                   handler
                   (assoc api-defaults :static {:resources "public"}))
                  {:port 8080})))

(defn watcher []
  (start-watch [{:path (:hot-dir env)
                 :event-types [:create :modify :delete]
                 :bootstrap (fn [path] (println "Starting to watch " path))
                 :callback (fn [_ _] (update-files))
                 :options {:recursive false}}])
  (start-watch [{:path (:hot-dir-wb env)
                 :event-types [:create :modify :delete]
                 :bootstrap (fn [path] (println "Starting to watch " path))
                 :callback (fn [_ _] (update-files))
                 :options {:recursive false}}]))

(defn -main
  [& args]
  (update-files)
  (watcher)
  (update-articles)
  (if args
    (do (cron/schedule {:hour (range 0 24)} update-articles)
        (start-server))
    ;;
    (let [file-to-upload (string/split-lines (slurp "to-upload.txt"))]
      (if (not= file-to-upload [""])
        (upload-from-file file-to-upload)
        (send-files)))))

(comment


  
  (start-server)
  (stop-server)
  
  )




(comment
)