(ns citilux-photo-upload.core
  (:require [babashka.fs :as fs]
            [clojure.string :as string]
            [clojure.java.shell :as sh]
            [config.core :refer [env]]
            [org.httpkit.server :as http]
            [hiccup.page :as hiccup]
            [hiccup.core :as h]
            #_[schejulure.core :as cron]
            [reitit.ring :as ring]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.util.response :as response]
            [citilux-photo-upload.upload :refer [upload-fotos]]
            [citilux-photo-upload.utils :refer [notify!
                                                check-dimm 
                                                delimiter
                                                get-article
                                                create-path
                                                report-imgs-1c!
                                                send-message!
                                                get-all-articles]])
  (:gen-class))

(set! *warn-on-reflection* true)

(def all-articles (atom []))
(defn update-articles! []
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
                           (let [article (get-article file)]
                             (cond
                               filter-errors? (when-not (some #{article} @all-articles) file)
                               :else (when (some #{article} @all-articles) file)))))]
    (if include-in?
      (filter #(string/starts-with? (get-article %) "IN") filtered)
      (filter #(not (string/starts-with? (get-article %) "IN")) filtered))))

(defn upload-from-file [art-to-upload]
  (let [err-arts (vec (filter-files {:filter-errors? true :files art-to-upload}))
        correct-arts (vec (filter-files {:files art-to-upload}))
        files (for [art correct-arts]
                (map str (fs/glob (str (:out-web+1c env) (create-path art)) "**{.jpeg,jpg}")))]
    (doseq [art correct-arts]
      (try
        (println (str "upload " art " to server"))
        (upload-fotos (str art))
        (catch Exception e (send-message! (str "upload on server caught exception: " (.getMessage e))))))
    (when (not-empty files)
      (notify! {:files (flatten files)}))
    (when (not-empty err-arts) (send-message! (str "На сайт не загружены из за ошибки артикула:\n" (apply str (for [art err-arts]
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
        hot-dir (filter-files {:files foto-hot-dir})
        hot-dir-wb (filter-files {:files (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg,png}"))})
        hot-dir-inlux (filter-files {:include-in? true
                                     :files (mapv str (fs/glob (:hot-dir env) "**{.jpg,jpeg,png}"))})
        hot-dir-wb-inlux (filter-files {:include-in? true
                                        :files (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg,png}"))})]
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
     :hot-dir-wb-inlux hot-dir-wb-inlux}))

(defn send-files!
  []
  (try
    (let [files (get-files)
          err-fotos (atom [])]

      (when (not-empty (concat (:videos files) (:videos_wb files)))

        (when (not-empty (:videos files))
          (doseq [file (:videos files)]
            (move-file file [(:out-source env)])))

        (when (not-empty (:videos_wb files))
          (doseq [file (:videos_wb files)]
            (move-file file [(:out-wb env)])))

        (notify! {:files (concat (:videos files) (:videos_wb files)) :heading "В папку WEB+1C_wildberries\n"}))

      (when (not-empty (concat (:videos-inlux files) (:videos-inlux_wb files)))

        (when (not-empty (:videos-inlux files))
          (doseq [file (:videos-inlux files)]
            (move-file file [(:out-source-inlux env)])))

        (when (not-empty (:videos-inlux_wb files))
          (doseq [file (:videos-inlux_wb files)]
            (move-file file [(:out-inlux-wb env)])))

        (notify! {:files (concat (:videos-inlux files) (:videos-inlux_wb files)) :heading "В папку INLUX\n"}))

      (when (not-empty (:hot-dir-wb files))
        (doseq [file (:hot-dir-wb files)]
          (if (:correct-dimm? (check-dimm file))
            (do
              (copy-file file [(:out-source-wb env)])
              (move-and-compress file [(:out-wb env)]))
            (swap! err-fotos conj file))))

      (when (not-empty (:hot-dir files))
        (doseq [file (:hot-dir files)]
          (if (:correct-dimm? (check-dimm file))
            (do (copy-file file [(:out-source env)])
                (move-and-compress file [(:out-web+1c env)]))
            (swap! err-fotos conj file))) 
        (report-imgs-1c! (mapv get-article
                               (remove #(contains? (set @err-fotos) %) (:hot-dir files)))))

      (when (not-empty (:hot-dir-inlux files))
        (doseq [file (:hot-dir-inlux files)]
          (if (:correct-dimm? (check-dimm file))
            (do
              (copy-file file [(:out-source-inlux env)])
              (move-and-compress file [(:out-inlux env)]))
            (swap! err-fotos conj file))))

      (when (not-empty (:hot-dir-wb-inlux files))
        (doseq [file (:hot-dir-wb-inlux files)]
          (if (:correct-dimm? (check-dimm file))
            (do
              (copy-file file [(:out-source-inlux-wb env)])
              (move-and-compress file [(:out-inlux-wb env)]))
            (swap! err-fotos conj file))))

      (when (not-empty (:wb-90 files))
        (doseq [file (:wb-90 files)]
          (if (:correct-dimm? (check-dimm file))
            (do
              (copy-file file [(:out-wb-90-source env)])
              (move-and-compress file [(:out-wb-90 env)]))
            (swap! err-fotos conj file))))

      (when (not-empty (:hot-dir-other files))
        (doseq [file (:hot-dir-other files)]
          (move-file file [(:out-web+1c env) (:out-source env)])))

      (when (not-empty (:err-files files))
        (send-message! (str "ошибки в названиях фото" (mapv fs/file-name (:err-files files)))))

      (when (not-empty (:err-files-inlux files))
        (send-message! (str "ошибки в названиях фото" (mapv fs/file-name (:err-files-inlux files)))))

      (when (not-empty (:hot-dir-inlux files)) (notify! {:files (:hot-dir-inlux files) :heading "В папку INLUX\n"}))
      (when (not-empty (:hot-dir-wb-inlux files)) (notify! {:files (:hot-dir-wb-inlux files) :heading "В папку INLUX-WB\n"}))

      (if (not-empty (concat (:to-upload files) (:hot-dir-wb files)))
        (do
          (doseq [art (:to-upload files)]
            (try
              (upload-fotos art)
              (println (str "upload " art " to server"))
              (catch Exception e (send-message! (str "upload on server caught exception: " (.getMessage e))))))
          (when (not-empty (:hot-dir files)) (notify! {:files (:hot-dir files)}))
          (when (not-empty (:hot-dir-wb files)) (notify! {:files (:hot-dir-wb files) :heading "В папку WEB+1C_wildberries\n"})))
        (send-message! "Новые фотографии отсутствуют"))

      (when (not-empty @err-fotos)
        (notify! {:files @err-fotos :heading "Ошибки в названиях фалов\n"
                                   :err? true})))

    (catch Exception e
      (send-message! (str "caught exception: " (.getMessage e)))
      (println (str "caught exception: " (.getMessage e))))))

(defn display-files [{:keys [files heading when-empty]}]
  [:div
   [:h2 {:class "text-lg pb-8"} heading]
   (if (> (count files) 0)
     (let [groups (into [] (frequencies (map get-article files)))]
       [:div
        [:ul
         (for [file groups]
           [:li (first file) " - " (last file) ".шт"])]])
     [:h2 when-empty])])

(defn form-page [_]
  (let [files (get-files)
        err-files (concat (:err-files-inlux files) (:err-files files))]
    (hiccup/html5 
     [:body
      [:head (hiccup/include-css "styles.css")]
      [:head (hiccup/include-js "htmx.js")]
      [:main {:class "container mx-auto grid grid-cols-2 gap-4"}
       [:div {:class "flex items-center justify-center"}
        (display-files {:files (:hot-dir files) :heading "Hot Dir" :when-empty "Фото отсутствуют"})]
       [:div {:class "flex items-center justify-center"}
        (display-files {:files (:hot-dir-wb files) :heading "Hot Dir WB" :when-empty "Фото отсутствуют"})]
       [:div {:class "flex items-center justify-center"}
        (display-files {:files (concat (:videos files) (:videos-inlux files))})]
       [:div {:class "flex items-center justify-center"}
        (display-files {:files (concat (:videos_wb files) (:videos-inlux_wb files))})] 
       (when (> (count err-files) 0)
         [:div
          [:h3 "Файлы с ошибками"]
          [:ul
           (for [file (concat err-files)]
             [:li (fs/file-name file)])]])]
      (when (> (count (concat
                       (:hot-dir files) (:videos files) (:videos-inlux files)
                       (:hot-dir-wb files) (:videos_wb files) (:videos-inlux_wb files))) 0)
        [:div {:class "flex flex-col items-center pt-10"}
         [:form {:method "POST" :action "/hot-dir-upload"}
          [:button {:type "submit" :class "btn btn-primary btn-wide"} "Загрузить"]]])
      [:br]
      [:div {:class "divider"}]
      [:br]
      [:div {:class "flex flex-col items-center"} [:form {:method "POST" :action "/upload"}
             [:textarea {:rows 10 :cols 45 :class "w-full h-20 p-2 textarea textarea-primary" :name "arts" :required true :placeholder "CL123456, CL234567"}]
             [:div {:class "flex flex-col items-center pt-10"} [:button {:type "submit" :class "btn btn-accent"} "Загрузить на сервер"]]]]
      [:div {:class "flex flex-col items-center pt-10"} [:button {:hx-post "/update" :hx-swap "outerHTML" :class "btn btn-success"} "Обновить список артикулов из 1с"]]])))

(defn hotdir-handler [_]
  (try (send-files!)
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
  (try (update-articles!)
       (h/html
        [:h2 "Успешно обновлен список артикулов"])
       (catch Exception e
         [:h2 "Ошибка"]
         [:h3 e])))

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
           [:main {:class "container mx-auto grid grid-cols-2 gap-4"}
            (when (> (count correct) 0)
              [:div {:class "flex flex-col items-center pt-10"}
               [:p "Фото по этим артикулам отправлены на сервер"]
               [:ul (for [art correct]
                      [:li art])]])
            (when (> (count err) 0)
              [:div {:class "flex flex-col items-center pt-10"}
               [:p "Артикула введеные с ошибками"]
               [:ul
                (for [e err]
                  [:li e])]])]
           [:div {:class "flex flex-col items-center pt-10"}
            [:a {:href "/" :class "btn btn-success"} "Вернутся на главную"]]])
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

(defn -main
  [& args]
  (update-articles!)
  (if args
    #_(do (cron/schedule {:hour (range 0 24)} update-articles!)
        (start-server))
    ;;
    (start-server)
    (let [file-to-upload (string/split-lines (slurp "to-upload.txt"))]
      (if (not= file-to-upload [""])
        (upload-from-file file-to-upload)
        (send-files!)))))

(comment
  (update-articles!)

  (start-server)
  (stop-server) 
  )