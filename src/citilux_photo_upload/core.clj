(ns citilux-photo-upload.core
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.java.shell :as sh]
            [config.core :refer [env]]
            [org.httpkit.server :as http]
            [hiccup.page :as hiccup]
            [cheshire.core :refer [generate-string]]
            [hiccup2.core :as h]
            [reitit.ring :as ring]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.util.response :as response]
            [citilux-photo-upload.upload :refer [upload-fotos]]
            [citilux-photo-upload.file-checker
             :refer [valid-regular-file-name?
                     valid-file-name-SMM?
                     valid-file-name-BANNERS?
                     valid-file-name-WEBBANNERS?
                     valid-file-name-NEWS?
                     valid-file-name-MAIL?
                     valid-file-name-SMM_ALL?
                     valid-file-name-BANNERS_ALL?
                     valid-file-name-WEBBANNERS_ALL?
                     valid-file-name-MAIL_ALL?
                     check-abris?
                     white?]]
            [citilux-photo-upload.utils :refer [notify!
                                                exist?
                                                copy-file
                                                move-file
                                                create-path-dimm
                                                check-dimm
                                                copy-abris
                                                get-article
                                                split-articles
                                                notify-msg-create
                                                report-imgs-1c!
                                                send-message!
                                                article-stat-handler
                                                general-stat-handler
                                                get-all-articles]])
  (:gen-class))

(set! *warn-on-reflection* true)
(def blocked (atom false))

(def all-articles (atom []))
(defn update-articles! []
  (println "updating articles")
  (reset! all-articles (get-all-articles)))

(defn hotdir-files []
  (mapv str (fs/glob (:hot-dir env) "**{.jpg,jpeg,png}")))

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

(defn filter-files [{:keys [filter-errors? files]}]
  (let [correct-arts (filter #(exist? % @all-articles) files)
        not-correct-arts (filter #(not (exist? % @all-articles)))]
    (cond
      filter-errors? not-correct-arts
      :else correct-arts)))

(defn get-files []
  (let [hotdir-files (hotdir-files)
        abris (filterv check-abris? hotdir-files)
        white (filterv white? hotdir-files)
        regular-hot-dir (vec (remove #(contains? (set (concat abris white)) %) (filterv valid-regular-file-name? hotdir-files)))
        SMM  (filterv valid-file-name-SMM? hotdir-files)
        BANNERS  (filterv valid-file-name-BANNERS? hotdir-files)
        WEBBANNERS  (filterv valid-file-name-WEBBANNERS? hotdir-files)
        NEWS  (filterv valid-file-name-NEWS? hotdir-files)
        MAIL  (filterv valid-file-name-MAIL? hotdir-files)
        SMM_ALL  (filterv valid-file-name-SMM_ALL? hotdir-files)
        BANNERS_ALL  (filterv valid-file-name-BANNERS_ALL? hotdir-files)
        WEBBANNERS_ALL  (filterv valid-file-name-WEBBANNERS_ALL? hotdir-files)
        MAIL_ALL  (filterv valid-file-name-MAIL_ALL? hotdir-files)
        to-upload (set (mapv get-article (filter valid-regular-file-name? hotdir-files)))
        all-valid (concat regular-hot-dir SMM BANNERS WEBBANNERS NEWS MAIL SMM_ALL BANNERS_ALL WEBBANNERS_ALL MAIL_ALL abris white)]
    {:err-files (vec (remove #(contains? (set all-valid) %) hotdir-files))
     :to-upload to-upload
     :smm SMM
     :banners BANNERS
     :webbanners WEBBANNERS
     :news NEWS
     :mail MAIL
     :smm-ALL SMM_ALL
     :banners-ALL BANNERS_ALL
     :webbanners-ALL WEBBANNERS_ALL
     :mail-ALL MAIL_ALL
     :abris abris
     :white white
     :all-valid all-valid
     :regular-hot-dir regular-hot-dir}))

(defn rp
  "retun root path for file"
  [file-name]
  (str (:hot-dir env) file-name))

(defn move-multiple [key files heading]
  (when (not-empty ((keyword key) files))
    (doseq [file ((keyword key) files)]
      (move-file file))
    (notify! {:files ((keyword key) files) :heading heading})))

(def message-log (atom []))
(defn add-to-message-log! [message]
  (swap! message-log conj message))

(defn send-files!
  []
  (try
    (let [files (get-files)
          err-fotos (atom [])]

      (when (not-empty (:regular-hot-dir files))
        (doseq [file (:regular-hot-dir files)]
          (if (check-dimm file)
            (do (copy-file file)
                (move-and-compress file))
            (swap! err-fotos conj file)))
        (add-to-message-log! (notify-msg-create {:files (:regular-hot-dir files) :heading "Загружены фото в папку\n"}))
        #_(report-imgs-1c! (set (mapv get-article
                                      (remove #(contains? (set @err-fotos) %) (:regular-hot-dir files))))))

      (when (not-empty (:smm files))
        (doseq [file (:smm files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:smm files) :heading "В папку СММ\n"})))

      (when (not-empty (:banners files))
        (doseq [file (:banners files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:banners files) :heading "В папку 05_COLLECTIONS_BANNERS\n"})))

      (when (not-empty (:news files))
        (doseq [file (:news files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:news files) :heading "В папку 05_COLLECTIONS_ADV\\02_NEWS\n"})))

      (when (not-empty (:mail files))
        (doseq [file (:mail files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:mail files) :heading "В папку 05_COLLECTIONS_ADV\\01_MAIL\n"})))

      (when (not-empty (:smm-ALL files))
        (doseq [file (:smm-ALL files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:smm-ALL files) :heading "В папку 05_COLLECTIONS_ADV\\03_SMM\n"})))

      (when (not-empty (:banners-ALL files))
        (doseq [file (:banners-ALL files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:banners-ALL files) :heading "В папку 05_COLLECTIONS_BANNERS\n"})))

      (when (not-empty (:webbanners-ALL files))
        (doseq [file (:webbanners-ALL files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:webbanners-ALL files) :heading "В папку 05_COLLECTIONS_WEB_BANNERS\n"})))

      (when (not-empty (:mail-ALL files))
        (doseq [file (:mail-ALL files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:mail-ALL files) :heading "В папку 05_COLLECTIONS_ADV\\01_MAIL\n"})))

      (when (not-empty (:white files))
        (doseq [file (:white files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:white files) :heading "В папку 04_SKU_PNG_WHITE\n"})))


      (when (not-empty (:abris files))
        (doseq [file (:abris files)]
          (copy-abris file))
        (add-to-message-log! (notify-msg-create {:files (:white files) :heading "В папку 04_SKU_PNG_WHITE\n"})))

      (when (not-empty (:err-files files))
        (send-message! (str "ошибки в названиях фото" (mapv fs/file-name (:err-files files)))))

      (when (not-empty @message-log)
        (send-message! (str/join "\n" @message-log))
        (swap! message-log empty))

      (if (not-empty (:to-upload files))
        (do
          (doseq [art (:to-upload files)]
            (try
              #_(upload-fotos art)
              (println (str "upload " art " to server"))
              (catch Exception e (send-message! (str "upload on server caught exception: " (.getMessage e))))))
          (when (not-empty (:to-upload files)) (notify! {:files (:to-upload files)})))
        (send-message! "Новые фотографии отсутствуют"))

      (when (not-empty @err-fotos)
        (notify! {:files @err-fotos :heading "Ошибки в размерах фото\n"
                  :err? true})))

    (catch Exception e
      (send-message! (str "caught exception send-message!: " (.getMessage e)))
      (println (str "caught exception send-message!: " (.getMessage e))))))


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
        err-files (:err-files files)]
    (hiccup/html5
     [:body
      [:head (hiccup/include-css "styles.css" "additional.css")]
      [:head (hiccup/include-js "htmx.js")]
      [:main {:class "container mx-auto grid grid-cols-1 gap-4"}
       [:div {:class "flex items-center justify-center"}
        (display-files {:files (:all-valid files) :heading "Hot Dir" :when-empty "Фото отсутствуют"})]
       (when (> (count err-files) 0)
         [:div
          [:h3 "Файлы с ошибками"]
          [:ul
           (for [file err-files]
             [:li (fs/file-name file)])]])]
      (when (> (count (:all-valid files)) 0)
        [:div {:class "flex flex-col items-center pt-10"}
         [:button {:type "submit" :hx-post "/hot-dir-upload" :hx-swap "outerHTML" :class "btn btn-primary btn-wide"} "Загрузить"
          [:img {:class "htmx-indicator" :src "https://htmx.org/img/bars.svg" :alt "Загрузка..."}]]])
      [:br]
      [:div {:class "divider"}]
      [:br]
      [:div {:class "flex flex-col items-center"} [:form {:method "POST" :action "/upload"}
                                                   [:textarea {:rows 10 :cols 45 :class "w-full h-20 p-2 textarea textarea-primary" :name "arts" :required true :placeholder "CL123456, CL234567"}]
                                                   [:div {:class "flex flex-col items-center pt-10"} [:button {:type "submit" :class "btn btn-accent"} "Загрузить на сервер"]]]]
      [:div {:class "flex flex-col items-center pt-10"} [:button {:hx-post "/update" :hx-swap "outerHTML" :class "btn btn-success"} "Обновить список артикулов из 1с"]]])))

(defn hotdir-handler [_]
  (try
    (if (true? @blocked)
      (-> (h/html
           [:div {:class "flex flex-col items-center pt-10"}
            [:h1 "Фото Загружаются кем то другим, подождите пару минут и повторите попытку"]
            [:a {:href "/" :class "p-10 btn btn-success"} "Обновить страницу"]])
          str)
      (do
        (swap! blocked (constantly true))
        (send-files!)
        (swap! blocked (constantly false))
        (-> (h/html
             [:div {:class "flex flex-col items-center pt-10"}
              [:h1 "Фото сжаты, разложены по папкам и отправлены на сервер"]
              [:a {:href "/" :class "p-10 btn btn-success"} "Обновить страницу"]])
            str)))

    (catch Exception e
      (swap! blocked (constantly false))
      (-> (h/html
           [:h1 "Что то пошло не так"]
           [:h2 e]
           [:a {:href "/"} "Обновить страницу"])
          str))))

(defn update-handler [_]
  (try (update-articles!)
       (str
        (h/html
         [:h2 "Успешно обновлен список артикулов"]))
       (catch Exception e
         (str [:h2 "Ошибка"]
              [:h3 e]))))

(defn upload-to-server [request]
  (let [params (-> request
                   :params
                   :arts
                   split-articles)
        arts (mapv str/trim params)
        _ (println "arts: " arts)
        err (filter-files {:filter-errors? true :files arts})
        correct (filter-files {:files arts})]
    (try (hiccup/html5
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
               [:p "Артикула введенные с ошибками"]
               [:ul
                (for [e err]
                  [:li e])]])]
           [:div {:class "flex flex-col items-center pt-10"}
            [:a {:href "/" :class "btn btn-success"} "Вернутся на главную"]]])
         (catch Exception e
           (hiccup/html5
            [:body
             [:head (hiccup/include-css "styles.css")]
             [:div {:class "flex flex-col items-center pt-10"}
              [:h1 "Что то пошло не так"]
              [:h2 e]
              [:a {:href "/" :class "btn btn-success"} "Вернутся на главную"]]])))))

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
     ["/mp-design-stat"
      {:get (fn [_]
              (-> (generate-string (general-stat-handler))
                  (response/response)
                  (response/header "content-type" "application/json")))}]
     ["/mp-design-stat/:article"
      {:get (fn [request]
              (-> (-> request
                      :path-params
                      :article
                      article-stat-handler
                      generate-string)
                  (response/response)
                  (response/header "content-type" "application/json")))}]
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
  []
  (update-articles!)
  (start-server))

(comment
  (update-articles!)

  (get-files)
  (send-files!)
  (start-server)
  (stop-server)



  (def files
    {:abris [],
     :err-files
     ["/home/li/TEMP/HOT_DIR/CL723330G_.jpg"
      "/home/li/TEMP/HOT_DIR/CL723_MAIL_ALL_.jpg"
      "/home/li/TEMP/HOT_DIR/CL723_NEWS_ALL_1.jpg"],
     :white ["/home/li/TEMP/HOT_DIR/CL723330G_00.jpg"],
     :banners ["/home/li/TEMP/HOT_DIR/CL723330G_BANNERS_1.jpg"],
     :mail ["/home/li/TEMP/HOT_DIR/CL723330G_MAIL_1.jpg"],
     :smm ["/home/li/TEMP/HOT_DIR/CL723330G_SMM_1.jpg"],
     :webbanners ["/home/li/TEMP/HOT_DIR/CL723330G_WEBBANNERS_1.jpg"],
     :smm-ALL ["/home/li/TEMP/HOT_DIR/CL723_SMM_ALL_1.jpg"],
     :to-upload #{"CL723330G"},
     :mail-ALL ["/home/li/TEMP/HOT_DIR/CL723_MAIL_ALL_1.jpg"],
     :banners-ALL ["/home/li/TEMP/HOT_DIR/CL723_BANNERS_ALL_1.jpg"],
     :webbanners-ALL ["/home/li/TEMP/HOT_DIR/CL723_WEBBANNERS_ALL_1.jpg"],
     :news ["/home/li/TEMP/HOT_DIR/CL723330G_NEWS_1.jpg"],
     :regular-hot-dir []})

  (when (not-empty (:smm files))
    (doseq [file (:smm files)]
      (move-file file))
    (notify! {:files (:smm files) :heading "TESR"}))

  (move-multiple "smm" (:smm files) "В папку СММ\n")
  (move-multiple "banners" (:banners files) "В папку 05_COLLECTIONS_BANNERS\n")
  (move-multiple "webbanners" (:webbanners files) "В папку 05_COLLECTIONS_WEB_BANNERS\n")
  (move-multiple "news" (:news files) "В папку 05_COLLECTIONS_ADV\\02_NEWS\n")
  (move-multiple "mail" (:mail files) "В папку 05_COLLECTIONS_ADV\\01_MAIL\n")
  (move-multiple "smm-ALL" (:smm-ALL files) "В папку 05_COLLECTIONS_ADV\\03_SMM\n")
  (move-multiple "banners-ALL" (:banners-ALL files) "В папку 05_COLLECTIONS_BANNERS\n")
  (move-multiple "webbanners-ALL" (:webbanners-ALL files) "В папку 05_COLLECTIONS_WEB_BANNERS\n")
  (move-multiple "mail-ALL" (:mail-ALL files) "В папку 05_COLLECTIONS_ADV\\01_MAIL\n")
  (move-multiple "white" (:white files) "В папку 04_SKU_PNG_WHITE\n")
  (when (not-empty (:abris files))
    (doseq [file (:abris files)]
      (copy-abris file)))




  (defn move-multiple [key files heading])


  (move-file "/home/li/TEMP/HOT_DIR/CL723330G_SMM_1.jpg")

  #_(when (not-empty ((keyword key) files))
      ((keyword key) files)
      #_(doseq [file ((keyword key) files)]
          (move-file file))
      #_(notify! {:files ((keyword key) files) :heading heading}))



)