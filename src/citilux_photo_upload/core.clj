(ns citilux-photo-upload.core
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [config.core :refer [env]]
            [org.httpkit.server :as http]
            [hiccup.page :as hiccup]
            [cheshire.core :refer [generate-string]]
            [hiccup2.core :as h]
            [reitit.ring :as ring]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.util.response :as response]
            [citilux-photo-upload.upload :refer [upload-fotos upload-3d]]
            [citilux-photo-upload.manuals :refer [manuals-page upload-instructions]]
            [citilux-photo-upload.upd-products :refer [upd-products-page upd-products]]
            [citilux-photo-upload.file-checker
             :refer [valid-regular-file-name?
                     valid-file-name-SMM?
                     valid-file-name-BANNERS?
                     valid-file-name-WEBBANNERS?
                     valid-file-name-NEWS?
                     valid-file-name-MAIL?
                     valid-file-name-SMM_ALL?
                     valid-file-name-NEWS_ALL?
                     valid-file-name-BANNERS_ALL?
                     valid-file-name-WEBBANNERS_ALL?
                     valid-file-name-MAIL_ALL?
                     valid-3d?
                     check-abris?
                     white?]]
            [citilux-photo-upload.utils :refer [notify!
                                                exist?
                                                create-dirs-ctructure 
                                                move-file 
                                                create-path-with-root
                                                check-dimm
                                                copy-abris
                                                get-article
                                                split-articles
                                                move-and-compress
                                                notify-msg-create
                                                report-imgs-1c!
                                                send-message!
                                                article-stat-handler
                                                general-stat-handler
                                                all-articles
                                                update-articles!]])
  (:gen-class))

(set! *warn-on-reflection* true)
(def blocked (atom false))


(defn hotdir-files []
  (mapv str (fs/glob (:hot-dir env) "**{.jpg,jpeg,png,zip}")))

(defn hotdir-files-wb []
  (mapv str (fs/glob (:hot-dir-wb env) "**{.jpg,jpeg,png}")))


(defn filter-files [{:keys [filter-errors? files]}]
  (let [correct-arts (vec (filter #(exist? % @all-articles) files))
        not-correct-arts (vec (remove #(exist? % @all-articles) files))]
    (if filter-errors?
      not-correct-arts
      correct-arts)))


(defn get-files []
  (let [hotdir-files       (hotdir-files)
        hotdir-files-wb    (hotdir-files-wb)
        abris              (filterv check-abris? hotdir-files)
        white              (filterv white? hotdir-files)
        white-wb           (filterv white? hotdir-files-wb)
        files-3d           (filterv valid-3d? hotdir-files)
        regular-hot-dir    (vec (remove #(contains? (set (concat abris white)) %) (filterv valid-regular-file-name? hotdir-files)))
        all-valid-wb       (vec (remove #(contains? (set white-wb) %) (filterv valid-regular-file-name? hotdir-files-wb)))
        SMM                (filterv valid-file-name-SMM? hotdir-files)
        BANNERS            (filterv valid-file-name-BANNERS? hotdir-files)
        WEBBANNERS         (filterv valid-file-name-WEBBANNERS? hotdir-files)
        NEWS               (filterv valid-file-name-NEWS? hotdir-files)
        MAIL               (filterv valid-file-name-MAIL? hotdir-files)
        SMM_ALL            (filterv valid-file-name-SMM_ALL? hotdir-files)
        NEWS_ALL           (filterv valid-file-name-NEWS_ALL? hotdir-files)
        BANNERS_ALL        (filterv valid-file-name-BANNERS_ALL? hotdir-files)
        WEBBANNERS_ALL     (filterv valid-file-name-WEBBANNERS_ALL? hotdir-files)
        MAIL_ALL           (filterv valid-file-name-MAIL_ALL? hotdir-files)
        to-upload          (set (mapv get-article (filter valid-regular-file-name? regular-hot-dir)))
        all-valid (concat regular-hot-dir SMM BANNERS WEBBANNERS NEWS MAIL SMM_ALL files-3d
                          BANNERS_ALL NEWS_ALL WEBBANNERS_ALL MAIL_ALL abris white)]
    {:err-files (vec (remove #(contains? (set all-valid) %) hotdir-files))
     :err-files-wb (vec (remove #(contains? (set all-valid-wb) %) hotdir-files-wb))
     :to-upload to-upload
     :smm SMM
     :banners BANNERS
     :webbanners WEBBANNERS
     :news NEWS
     :mail MAIL
     :3d files-3d
     :smm-ALL SMM_ALL
     :news-ALL NEWS_ALL
     :banners-ALL BANNERS_ALL
     :webbanners-ALL WEBBANNERS_ALL
     :mail-ALL MAIL_ALL
     :abris abris
     :white white
     :all-valid all-valid
     :all-valid-wb all-valid-wb
     :regular-hot-dir regular-hot-dir
     :regular-hot-dir-wb all-valid-wb}))

(def message-log (atom []))
(defn add-to-message-log! [message]
  (swap! message-log conj message))

(defn send-files!
  []
  (try
    (let [files (get-files)
          err-fotos (atom [])]

      (when (not-empty (:abris files))
        (doseq [file (:abris files)]
          (if (check-dimm file)
            (copy-abris file)
            (swap! err-fotos conj file)))
        (add-to-message-log! (notify-msg-create {:files (:white files) :heading "В папку 01_PRODUCTION_FILES/01_ABRIS/\n"})))

      (when (not-empty (:regular-hot-dir files))
        (doseq [file (:regular-hot-dir files)]
          (if (check-dimm file)
            (let [out-path-source (str (:out-path env) (create-path-with-root file "03_SOURCE_1_1/"))
                  out-path (str (:out-path env) (create-path-with-root file "04_SKU_INTERNAL_1_1/"))]
              (when-not (fs/exists? out-path) (fs/create-dirs out-path-source))
              (when-not (fs/exists? out-path) (fs/create-dirs out-path))
              (fs/copy file out-path-source {:replace-existing true})
              (move-and-compress file "04_SKU_INTERNAL_1_1/"))
            (swap! err-fotos conj file)))
        (add-to-message-log! (notify-msg-create {:files (:regular-hot-dir files) :heading "Загружены фото в папку\n"}))
        (when-not (:debug env)
          (report-imgs-1c! (set (mapv get-article
                                      (remove #(contains? (set @err-fotos) %) (:regular-hot-dir files)))))))

      (when (not-empty (:regular-hot-dir-wb files))
        (doseq [file (:regular-hot-dir-wb files)]
          (if (check-dimm file)
            (let [out-path-source (str (:out-path env) (create-path-with-root file "03_SOURCE_3_4/"))
                  out-path (str (:out-path env) (create-path-with-root file "04_SKU_INTERNAL_3_4/"))]
              (when-not (fs/exists? out-path) (fs/create-dirs out-path-source))
              (when-not (fs/exists? out-path) (fs/create-dirs out-path))
              (fs/copy file out-path-source {:replace-existing true})
              (move-and-compress file "04_SKU_INTERNAL_3_4/"))
            (swap! err-fotos conj file)))
        (add-to-message-log! (notify-msg-create {:files (:regular-hot-dir files) :heading "Загружены фото в папку\n"}))
        (when-not (:debug env)
          (report-imgs-1c! (set (mapv get-article
                                      (remove #(contains? (set @err-fotos) %) (:regular-hot-dir files)))))))

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

      (when (not-empty (:webbanners files))
        (doseq [file (:webbanners files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:webbanners files) :heading "В папку 05_COLLECTIONS_WEB_BANNERS\n"})))


      (when (not-empty (:mail-ALL files))
        (doseq [file (:mail-ALL files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:mail-ALL files) :heading "В папку 05_COLLECTIONS_ADV\\01_MAIL\n"})))

      (when (not-empty (:news-ALL files))
        (doseq [file (:news-ALL files)]
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:news-ALL files) :heading "В папку 05_COLLECTIONS_ADV\\02_NEWS/\n"})))
      
      (when (not-empty (:3d files))
        (doseq [file (:3d files)]
          (let [out-path (str (:out-path env) (create-path-with-root file "04_SKU_3D_FOR_DESIGNERS/"))]
            (when-not (fs/exists? out-path) (fs/create-dirs out-path))
            (fs/move file out-path {:replace-existing true :atomic-move true})
            (upload-3d (get-article file))))
        (add-to-message-log! (notify-msg-create {:files (:3d files) :heading "В папку 04_SKU_3D_FOR_DESIGNERS/\n"})))

      (when (not-empty (:white files))
        (doseq [file (:white files)]
          (let [path (str (:out-path env) (create-path-with-root file "04_SKU_PNG_WHITE/"))]
            (fs/create-dirs path)
            (fs/copy file path {:replace-existing true})
            (fs/delete-if-exists file)))
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
              (when-not (:debug env)
                (upload-fotos art))
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
  (let [files (get-files)]
    (hiccup/html5
     [:body
      [:head (hiccup/include-css "styles.css" "additional.css")]
      [:head (hiccup/include-js "htmx.js")]
      [:main {:class "container mx-auto grid grid-cols-2 gap-4"}
       [:div {:class "flex items-center justify-center"}
        (display-files {:files (:all-valid files) :heading "Hot Dir" :when-empty "Фото отсутствуют"})]
       [:div {:class "flex items-center justify-center"}
        (display-files {:files (:all-valid-wb files) :heading "Hot Dir WB" :when-empty "Фото отсутствуют"})]
       (when (> (count (:err-files files)) 0)
         [:div
          [:h3 "Файлы с ошибками"]
          [:ul
           (for [file (:err-files files)]
             [:li (fs/file-name file)])]])
       (when (> (count (:err-files-wb files)) 0)
         [:div
          [:h3 "Файлы с ошибками wb"]
          [:ul
           (for [file (:err-files-wb files)]
             [:li (fs/file-name file)])]])]
      (when (> (count (concat (:all-valid files) (:all-valid-wb files))) 0)
        [:div {:class "flex flex-col items-center pt-10"}
         [:button {:type "submit" :hx-post "/hot-dir-upload" :hx-swap "outerHTML" :class "btn btn-primary btn-wide"} "Загрузить"
          [:img {:class "htmx-indicator" :src "https://htmx.org/img/bars.svg" :alt "Загрузка..."}]]])
      [:br]
      [:div {:class "divider"}]
      [:br]
      [:div {:class "flex flex-col items-center"} [:form {:method "POST" :action "/upload"}
                                                   [:textarea {:rows 10 :cols 45 :class "w-full h-20 p-2 textarea textarea-primary" :name "arts" :required true :placeholder "CL123456, CL234567"}]
                                                   [:div {:class "flex flex-col items-center pt-10"} [:button {:type "submit" :class "btn btn-accent"} "Загрузить на сервер"]]]]
      [:div {:class "flex flex-col items-center pt-10"} 
       [:button {:hx-post "/update" :hx-swap "outerHTML" :class "btn btn-success mb-4"} "Обновить список артикулов из 1с"]
       [:a {:href "/upd-products" :class "btn btn-info mb-4"} "Обновить продукты на сервере"]
       [:a {:href "/manuals" :class "btn btn-secondary"} "Загрузить инструкции"]]])))

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

(defn create-dirs-handler [_]
  (try (create-dirs-ctructure)
       (str
        (h/html
         [:h2 "Успешно перегененированы директории"]))
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
              (doseq [art correct]
                (upload-fotos art))
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
     ["/manuals"
      {:get (fn [request]
              (-> (manuals-page request)
                  (response/response)
                  (response/header "content-type" "text/html")))}]
     ["/upd-products"
      {:get (fn [request]
              (-> (upd-products-page request)
                  (response/response)
                  (response/header "content-type" "text/html")))
       :post (fn [request]
               (-> (upd-products request)
                   (response/response)
                   (response/header "content-type" "text/html")))}]
     ["/upload-instructions"
      {:post (fn [request]
               (-> (upload-instructions request)
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
                   (response/header "content-type" "text/html")))}]
     ["/create-dirs"
      {:get (fn [request]
               (-> (create-dirs-handler request)
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


  (copy-abris (first (:abris (get-files))))
  
  (first (:abris (get-files)))

  (create-path-with-root "/home/k0t3sla/TMP/HOT_DIR/CL237B310_00.jpg" "04_SKU_PNG_WHITE/")

  (copy-abris "/home/k0t3sla/TMP/HOT_DIR/CL237B310_31.jpg")




  
  )
