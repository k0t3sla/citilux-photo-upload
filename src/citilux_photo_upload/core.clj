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
            [citilux-photo-upload.proxy :as proxy]
            [citilux-photo-upload.upload :refer [upload-fotos upload-3d]]
            [citilux-photo-upload.manuals :refer [manuals-page upload-instructions]]
            [citilux-photo-upload.upd-products :refer [upd-products-page upd-products reindex-site!]]
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
(def max-progress-entries 120)
(defonce upload-progress
  (atom {:running? false
         :started-at nil
         :finished-at nil
         :stage nil
         :entries []
         :stage-totals {}
         :stage-processed {}}))

(defn- now-ts []
  (str (java.time.Instant/now)))

(defn reset-progress!
  ([] (reset-progress! {}))
  ([stage-totals]
   (reset! upload-progress {:running? true
                            :started-at (now-ts)
                            :finished-at nil
                            :stage "start"
                            :entries []
                            :stage-totals stage-totals
                            :stage-processed {}})))

(defn append-progress! [entry]
  (swap! upload-progress update :entries
         (fn [entries]
           (let [entry-data (if (string? entry)
                              {:ts (now-ts) :message entry :stage nil :kind :info}
                              (assoc entry :ts (or (:ts entry) (now-ts))))
                 out (conj (vec entries) entry-data)]
             (if (> (count out) max-progress-entries)
               (subvec out (- (count out) max-progress-entries))
               out)))))

(defn log-file-progress! [stage file-name action]
  (swap! upload-progress update-in [:stage-processed stage] (fnil inc 0))
  (append-progress! {:message (str action ": " file-name)
                     :stage stage
                     :kind :file}))

(defn set-progress-stage! [stage]
  (swap! upload-progress assoc :stage stage)
  (append-progress! {:message (str "Этап: " stage)
                     :stage stage
                     :kind :stage}))

(defn finish-progress! []
  (swap! upload-progress assoc :running? false :finished-at (now-ts)))

(defn- calculate-stage-totals [files]
  {"abris" (count (:abris files))
   "regular-hot-dir" (count (:regular-hot-dir files))
   "regular-hot-dir-wb" (count (:regular-hot-dir-wb files))
   "smm" (count (:smm files))
   "banners" (count (:banners files))
   "3d" (count (:3d files))
   "white" (count (:white files))
   "upload-to-server" (count (:to-upload files))})

(defn- stage-color [stage]
  (case stage
    "abris" "text-blue-700"
    "regular-hot-dir" "text-green-700"
    "regular-hot-dir-wb" "text-emerald-700"
    "smm" "text-pink-700"
    "banners" "text-orange-700"
    "news" "text-purple-700"
    "mail" "text-cyan-700"
    "3d" "text-indigo-700"
    "white" "text-slate-700"
    "upload-to-server" "text-red-700"
    "done" "text-lime-700"
    "text-base-content"))

(defn- grouped-progress [entries]
  (->> entries
       (reduce (fn [acc entry]
                 (let [stage (or (:stage entry) "other")]
                   (update acc stage (fnil conj []) entry)))
               {})
       (sort-by key)))

(defn progress-ui-fragment []
  (let [{:keys [running? stage entries started-at finished-at stage-totals stage-processed]} @upload-progress
        total-planned (reduce + 0 (vals stage-totals))
        total-done (reduce + 0 (vals stage-processed))
        current-total (if (= stage "done")
                        total-planned
                        (get stage-totals stage 0))
        current-processed (if (= stage "done")
                            total-done
                            (get stage-processed stage 0))]
    [:div {:id "upload-progress-panel"
           :class "mt-6 p-4 rounded border"
           :hx-get "/hot-dir-upload/progress-ui"
           :hx-trigger (if running? "every 2s" "none")
           :hx-swap "outerHTML"}
     [:h3 {:class "font-bold mb-2"} "Лог загрузки"]
     [:div {:class "text-sm mb-2"} (str "Статус: " (if running? "в процессе" "завершено"))]
     [:div {:class "text-sm mb-2"} (str "Этап: " (or stage "-"))]
     [:div {:class "text-sm mb-2 font-medium"} (str "Обработано: " current-processed "/" current-total)]
     [:div {:class "text-xs mb-2"} (str "Старт: " (or started-at "-") " | Финиш: " (or finished-at "-"))]
     [:div {:id "upload-log-scroll" :class "max-h-64 overflow-auto bg-base-200 p-2 rounded"}
      (if (seq entries)
        [:div
         (for [[stage-name stage-entries] (grouped-progress entries)]
           (let [file-count (count (filter #(= :file (:kind %)) stage-entries))
                 info-count (count (remove #(= :file (:kind %)) stage-entries))]
             [:div {:class "mb-3 border-l-2 pl-3"}
              [:div {:class (str "text-sm font-semibold " (stage-color stage-name))}
               (str stage-name "  |  files: " file-count "  |  events: " info-count)]
              [:ul
               (for [{:keys [ts message]} stage-entries]
                 [:li {:class "text-xs"} (str ts "  " message)])]]))]
        [:div {:class "text-xs opacity-70"} "Лог пока пуст"])]]))


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

(defn- run-safe! [label f]
  (try
    (f)
    (catch Exception e
      ;; Network integrations must not stop file distribution pipeline.
      (append-progress! (str "warn [" label "]: " (.getMessage e)))
      (println (str "warn [" label "]: " (.getMessage e))))))

(defn send-files!
  []
  (try
    (let [files (get-files)
          err-fotos (atom [])]

      (when (not-empty (:abris files))
        (set-progress-stage! "abris")
        (doseq [file (:abris files)]
          (log-file-progress! "abris" (fs/file-name file) "copy")
          (if (check-dimm file)
            (copy-abris file)
            (swap! err-fotos conj file)))
        (add-to-message-log! (notify-msg-create {:files (:white files) :heading "В папку 01_PRODUCTION_FILES/01_ABRIS/\n"})))

      (when (not-empty (:regular-hot-dir files))
        (set-progress-stage! "regular-hot-dir")
        (doseq [file (:regular-hot-dir files)]
          (log-file-progress! "regular-hot-dir" (fs/file-name file) "copy/move/compress")
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
          (run-safe! "report-imgs-1c regular-hot-dir"
                     (fn []
                       (report-imgs-1c! (set (mapv get-article
                                                   (remove #(contains? (set @err-fotos) %) (:regular-hot-dir files)))))))))

      (when (not-empty (:regular-hot-dir-wb files))
        (set-progress-stage! "regular-hot-dir-wb")
        (doseq [file (:regular-hot-dir-wb files)]
          (log-file-progress! "regular-hot-dir-wb" (fs/file-name file) "copy/move/compress")
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
          (run-safe! "report-imgs-1c regular-hot-dir-wb"
                     (fn []
                       (report-imgs-1c! (set (mapv get-article
                                                   (remove #(contains? (set @err-fotos) %) (:regular-hot-dir files)))))))))

      (when (not-empty (:smm files))
        (set-progress-stage! "smm")
        (doseq [file (:smm files)]
          (log-file-progress! "smm" (fs/file-name file) "move")
          (move-file file))
        (add-to-message-log! (notify-msg-create {:files (:smm files) :heading "В папку СММ\n"})))

      (when (not-empty (:banners files))
        (set-progress-stage! "banners")
        (doseq [file (:banners files)]
          (log-file-progress! "banners" (fs/file-name file) "move")
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
        (set-progress-stage! "3d")
        (doseq [file (:3d files)]
          (log-file-progress! "3d" (fs/file-name file) "move/upload")
          (let [out-path (str (:out-path env) (create-path-with-root file "04_SKU_3D_FOR_DESIGNERS/"))]
            (when-not (fs/exists? out-path) (fs/create-dirs out-path))
            (fs/move file out-path {:replace-existing true :atomic-move true})
            (upload-3d (get-article file))))
        (add-to-message-log! (notify-msg-create {:files (:3d files) :heading "В папку 04_SKU_3D_FOR_DESIGNERS/\n"})))

      (when (not-empty (:white files))
        (set-progress-stage! "white")
        (doseq [file (:white files)]
          (log-file-progress! "white" (fs/file-name file) "copy")
          (let [path (str (:out-path env) (create-path-with-root file "04_SKU_PNG_WHITE/"))]
            (fs/create-dirs path)
            (fs/copy file path {:replace-existing true})
            (fs/delete-if-exists file)))
        (add-to-message-log! (notify-msg-create {:files (:white files) :heading "В папку 04_SKU_PNG_WHITE\n"})))

      (when (not-empty (:err-files files))
        (run-safe! "send-message err-files"
                   #(send-message! (str "ошибки в названиях фото" (mapv fs/file-name (:err-files files))))))

      (when (not-empty @message-log)
        (run-safe! "send-message summary"
                   #(send-message! (str/join "\n" @message-log)))
        (swap! message-log empty))

      (if (not-empty (:to-upload files))
        (do
          (set-progress-stage! "upload-to-server")
          (doseq [art (:to-upload files)]
            (log-file-progress! "upload-to-server" art "upload")
            (try
              (when-not (:debug env)
                (upload-fotos art))
              (println (str "upload " art " to server"))
              (catch Exception e
                (append-progress! (str "warn [upload-fotos]: " art " -> " (.getMessage e)))
                (run-safe! "send-message upload-fotos exception"
                           #(send-message! (str "upload on server caught exception: " (.getMessage e)))))))
          (when (not-empty (:to-upload files))
            (run-safe! "notify upload complete" #(notify! {:files (:to-upload files)}))))
        (run-safe! "send-message no-new-photos" #(send-message! "Новые фотографии отсутствуют")))

      (when (not-empty @err-fotos)
        (run-safe! "notify err-fotos"
                   #(notify! {:files @err-fotos :heading "Ошибки в размерах фото\n"
                              :err? true}))))

    (catch Exception e
      (append-progress! (str "error: " (.getMessage e)))
      (run-safe! "send-message top-level-exception"
                 #(send-message! (str "caught exception send-files!: " (.getMessage e))))
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
         [:button {:type "submit" :hx-post "/hot-dir-upload" :hx-target "#upload-live-log" :hx-swap "innerHTML" :class "btn btn-primary btn-wide"} "Загрузить"
          [:img {:class "htmx-indicator" :src "https://htmx.org/img/bars.svg" :alt "Загрузка..."}]]])
      [:div {:id "upload-live-log" :class "container mx-auto"} (progress-ui-fragment)]
      [:br]
      [:div {:class "divider"}]
      [:br]
      [:div {:class "flex flex-col items-center"} [:form {:method "POST" :action "/upload"}
                                                   [:textarea {:rows 10 :cols 45 :class "w-full h-20 p-2 textarea textarea-primary" :name "arts" :required true :placeholder "CL123456, CL234567"}]
                                                   [:div {:class "flex flex-col items-center pt-10"} [:button {:type "submit" :class "btn btn-accent"} "Загрузить на сервер"]]]]
      [:div {:class "flex flex-col items-center pt-10"} 
       [:div {:class "mb-4"}
        (if (proxy/any-proxy-alive?)
          [:span {:class "inline-block px-4 py-2 rounded text-white bg-green-600"} "Proxy: active"]
          [:span {:class "inline-block px-4 py-2 rounded text-white bg-red-600"} "Proxy: inactive"])]
       [:a {:href "/proxy" :class "btn btn-outline mb-4"} "Управление прокси"]
       [:button {:hx-post "/update" :hx-swap "outerHTML" :class "btn btn-success mb-4"} "Обновить список артикулов из 1с"]
       [:a {:href "/upd-products" :class "btn btn-info mb-4"} "Обновить продукты на сервере"]
       [:a {:href "/reindex" :class "btn btn-secondary mb-4"} "Переиндексировать сайт"]
       [:a {:href "/manuals" :class "btn btn-secondary"} "Загрузить инструкции"]]
      [:script "
function scrollUploadLogToBottom() {
  var el = document.getElementById('upload-log-scroll');
  if (el) {
    el.scrollTop = el.scrollHeight;
  }
}
document.addEventListener('DOMContentLoaded', scrollUploadLogToBottom);
document.body.addEventListener('htmx:afterSwap', function (evt) {
  var target = evt && evt.detail && evt.detail.target;
  if (!target) return;
  if (target.id === 'upload-live-log' || target.id === 'upload-progress-panel') {
    scrollUploadLogToBottom();
  }
});
"]])))

(defn json-response [data]
  (-> (generate-string data)
      (response/response)
      (response/header "content-type" "application/json")))

(defn proxy-list-handler [_]
  (json-response {:proxies (proxy/list-proxies)}))

(defn- parse-json-safely [s]
  (try
    (cheshire.core/parse-string s true)
    (catch Exception _ nil)))

(defn proxy-add-handler [request]
  (try
    (let [body (slurp (:body request))
          payload (when-not (str/blank? body) (parse-json-safely body))
          links (or (:links payload) body)
          result (proxy/add-proxies-validated! links)]
      (if (pos? (:invalid result))
        (-> (json-response {:error "Часть ссылок отклонена"
                            :invalid-items (:invalid-items result)
                            :added (:added result)
                            :invalid (:invalid result)})
            (response/status 400))
        (json-response result)))
    (catch Exception e
      (-> (json-response {:error (.getMessage e)})
          (response/status 400)))))

(defn proxy-remove-handler [request]
  (try
    (let [body (slurp (:body request))
          payload (when-not (str/blank? body) (parse-json-safely body))
          ids-or-urls (or (:items payload)
                          (when (string? body)
                            (->> (str/split-lines body) (map str/trim) (remove str/blank?)))
                          [])
          result (proxy/remove-proxies! ids-or-urls)]
      (json-response result))
    (catch Exception e
      (-> (json-response {:error (.getMessage e)})
          (response/status 400)))))

(defn proxy-refresh-handler [_]
  (json-response {:proxies (proxy/refresh-proxy-status!)}))

(defn proxy-working-handler [_]
  (if-let [raw-url (proxy/get-working-proxy-url)]
    (-> (response/response raw-url)
        (response/header "content-type" "text/plain"))
    (-> (response/response "")
        (response/status 404)
        (response/header "content-type" "text/plain"))))

(defn- status-badge [{:keys [alive?]}]
  (if alive?
    [:span {:class "px-2 py-1 rounded text-white bg-green-600"} "UP"]
    [:span {:class "px-2 py-1 rounded text-white bg-red-600"} "DOWN"]))

(defn- normalize-to-vec [value]
  (cond
    (nil? value) []
    (vector? value) value
    (sequential? value) (vec value)
    :else [value]))

(defn proxy-panel-fragment
  ([] (proxy-panel-fragment {}))
  ([{:keys [flash preserve-state? links-value]
     :or {flash nil preserve-state? true links-value ""}}]
   (let [proxies (proxy/list-proxies)
         rows (for [{:keys [id type server port latency-ms last-check-at last-error] :as p} proxies]
                (let [checkbox-id (str "proxy-checkbox-" (str/replace id #"[^A-Za-z0-9_-]" "_"))]
                [:tr
                 [:td [:input {:id checkbox-id
                               :type "checkbox"
                               :name "items"
                               :value id
                               :hx-preserve (when preserve-state? "true")}]]
                 [:td [:code id]]
                 [:td (name type)]
                 [:td server]
                 [:td port]
                 [:td (status-badge p)]
                 [:td (or latency-ms "-")]
                 [:td (or last-check-at "-")]
                 [:td (or last-error "-")]
                 [:td
                  (if-let [raw-url (:raw-url p)]
                    [:button {:type "button"
                              :title "Скопировать proxy-ссылку"
                              :class "text-xl copy-proxy-btn"
                              :data-proxy-url raw-url}
                     "📋"]
                    "-")]]))]
     [:div {:id "proxy-panel"}
      [:div {:id "proxy-loading-indicator"
             :class "htmx-indicator mb-4 flex items-center gap-2 text-sm text-gray-600"}
       [:img {:src "https://htmx.org/img/bars.svg" :alt "Загрузка..." :class "w-5 h-5"}]
       [:span "Обновление..."]]
      (when flash
        [:div {:class (str "mb-4 p-3 rounded text-white "
                           (if (= :error (:type flash)) "bg-red-600" "bg-green-600"))}
         [:div (:message flash)]
         (when-let [items (:details flash)]
           [:ul {:class "mt-2 list-disc pl-4"}
            (for [{:keys [input error]} items]
              [:li (str (if (str/blank? input) "<empty>" input) " -> " error)])])])
      [:div {:class "mb-4"}
       (if (proxy/any-proxy-alive?)
         [:span {:class "inline-block px-4 py-2 rounded text-white bg-green-600"} "Есть рабочие прокси"]
         [:span {:class "inline-block px-4 py-2 rounded text-white bg-red-600"} "Рабочих прокси нет"])]
      [:form {:hx-post "/proxy/ui/refresh"
              :hx-target "#proxy-panel"
              :hx-swap "outerHTML"
              :hx-indicator "#proxy-loading-indicator"
              :class "mb-6"}
       [:button {:type "submit" :class "btn btn-primary"} "Обновить статусы (ping)"]]
      [:div {:class "mb-8"}
       [:h2 {:class "text-lg mb-2"} "Добавить прокси списком"]
       [:form {:hx-post "/proxy/ui/add"
               :hx-target "#proxy-panel"
               :hx-swap "outerHTML"
               :hx-indicator "#proxy-loading-indicator"}
        [:textarea {:name "links"
                    :id "proxy-links-input"
                    :hx-preserve (when preserve-state? "true")
                    :rows 8
                    :value links-value
                    :class "w-full p-2 textarea textarea-primary"
                    :placeholder "Вставьте ссылки по одной на строку..."}]
        [:div {:class "mt-3"}
         [:button {:type "submit" :class "btn btn-success"} "Добавить"]]]]
      [:div
       [:h2 {:class "text-lg mb-2"} "Сохраненные прокси"]
       [:form {:hx-post "/proxy/ui/remove"
               :hx-target "#proxy-panel"
               :hx-swap "outerHTML"
               :hx-indicator "#proxy-loading-indicator"}
        [:table {:class "table w-full"}
         [:thead
          [:tr
           [:th ""]
           [:th "ID"]
           [:th "Type"]
           [:th "Server"]
           [:th "Port"]
           [:th "Status"]
           [:th "Latency"]
           [:th "Last check"]
           [:th "Error"]
           [:th "Copy"]]]
         (into [:tbody] rows)]
        [:div {:class "mt-3"}
         [:button {:type "submit" :class "btn btn-error"} "Удалить выбранные"]]]]])))

(defn html-fragment-response [fragment]
  (-> (str (h/html fragment))
      (response/response)
      (response/header "content-type" "text/html; charset=utf-8")))

(defn proxy-page [_]
  (hiccup/html5
   [:body
    [:head (hiccup/include-css "styles.css" "additional.css")]
    [:head (hiccup/include-js "htmx.js")]
    [:main {:class "container mx-auto p-6"}
     [:div {:class "flex items-center justify-between mb-6"}
      [:h1 {:class "text-2xl font-bold"} "Proxy Manager"]
      [:a {:href "/" :class "btn btn-secondary"} "На главную"]]
     [:div {:class "mb-4 flex items-center gap-3"}
      [:label {:for "auto-refresh-toggle" :class "font-medium"} "Auto-refresh"]
      [:input {:id "auto-refresh-toggle" :type "checkbox" :checked true}]
      [:span {:id "auto-refresh-state" :class "text-sm"} "ON (60s)"]]
     ;; polling updates status/table every 10s without full page reload
     [:div {:id "proxy-panel-polling"
            :hx-get "/proxy/ui/panel"
            :hx-trigger "every 60s"
            :hx-target "#proxy-panel"
            :hx-swap "outerHTML"
            :hx-indicator "#proxy-loading-indicator"}
      (proxy-panel-fragment {:preserve-state? true})]
     [:script "
document.addEventListener('change', function (e) {
  if (e.target && e.target.id === 'auto-refresh-toggle') {
    var polling = document.getElementById('proxy-panel-polling');
    var state = document.getElementById('auto-refresh-state');
    var enabled = !!e.target.checked;
    polling.setAttribute('hx-trigger', enabled ? 'every 60s' : 'none');
    if (state) state.textContent = enabled ? 'ON (60s)' : 'OFF';
    if (window.htmx && polling) window.htmx.process(polling);
  }
});

document.addEventListener('click', function (e) {
  var btn = e.target && e.target.closest ? e.target.closest('.copy-proxy-btn') : null;
  if (!btn) return;
  var url = btn.getAttribute('data-proxy-url');
  if (!url) return;
  navigator.clipboard.writeText(url).then(function () {
    var old = btn.textContent;
    btn.textContent = '✅';
    setTimeout(function () { btn.textContent = old; }, 900);
  });
});
"]]]))


(defn proxy-ui-add-handler [request]
  (let [links (get-in request [:params :links] "")]
    (if (str/blank? links)
      (html-fragment-response (proxy-panel-fragment {:flash {:type :error :message "Вставьте хотя бы одну ссылку"}
                                                     :preserve-state? true
                                                     :links-value links}))
      (let [result (proxy/add-proxies-validated! links)]
        (proxy/refresh-proxy-status!)
        (if (pos? (:invalid result))
          (html-fragment-response
           (proxy-panel-fragment {:flash {:type :error
                                          :message (str "Отклонено ссылок: " (:invalid result))
                                          :details (:invalid-items result)}
                                  :preserve-state? true
                                  :links-value links}))
          (html-fragment-response
           (proxy-panel-fragment {:flash {:type :success
                                          :message (str "Добавлено прокси: " (:added result))}
                                  :preserve-state? false
                                  :links-value ""})))))))

(defn proxy-ui-remove-handler [request]
  (let [items (-> request :params :items normalize-to-vec)]
    (when (seq items)
      (proxy/remove-proxies! items))
    (html-fragment-response (proxy-panel-fragment {:flash {:type :success :message "Выбранные прокси удалены"}
                                                   :preserve-state? false
                                                   :links-value ""}))))

(defn proxy-ui-refresh-handler [_]
  (proxy/refresh-proxy-status!)
  (html-fragment-response (proxy-panel-fragment {:flash {:type :success :message "Статусы обновлены"}
                                                 :preserve-state? false
                                                 :links-value ""})))

(defn proxy-ui-panel-handler [_]
  (html-fragment-response (proxy-panel-fragment {:preserve-state? true})))

(defn hotdir-handler [_]
  (try
    (if (true? @blocked)
      (-> (h/html (progress-ui-fragment)) str)
      (do
        (swap! blocked (constantly true))
        (let [files (get-files)]
          (reset-progress! (calculate-stage-totals files)))
        (append-progress! "Запущена обработка файлов")
        (future
          (try
            (send-files!)
            (set-progress-stage! "done")
            (append-progress! "Обработка завершена")
            (finally
              (finish-progress!)
              (swap! blocked (constantly false)))))
        (-> (h/html (progress-ui-fragment)) str)))

    (catch Exception e
      (swap! blocked (constantly false))
      (finish-progress!)
      (append-progress! (str "error: " (.getMessage e)))
      (-> (h/html (progress-ui-fragment)) str))))

(defn hotdir-progress-handler [_]
  (let [{:keys [running? stage entries started-at finished-at]} @upload-progress]
    {:running running?
     :stage stage
     :entries entries
     :started-at started-at
     :finished-at finished-at}))

(defn hotdir-progress-ui-handler [_]
  (-> (h/html (progress-ui-fragment)) str))

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

(defn reindex-handler [_]
  (try (reindex-site!)
       (str
        (h/html
         [:h2 "Успешно переиндексирован сайт"]
         [:a {:href "/" :class "btn btn-success"} "Вернутся на главную"]))
       (catch Exception e
         (str [:h2 "Ошибка"]
              [:h3 e]))))

(def handler
  (ring/ring-handler
   (ring/router
    [["/"
      {:get (fn [request]
              (-> (form-page request)
                  (response/response)
                  (response/header "content-type" "text/html")))}]
     ["/reindex"
      {:get (fn [request]
              (-> (reindex-handler request)
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
     ["/hot-dir-upload/progress"
      {:get (fn [request]
              (-> (hotdir-progress-handler request)
                  (generate-string)
                  (response/response)
                  (response/header "content-type" "application/json")))}]
     ["/hot-dir-upload/progress-ui"
      {:get (fn [request]
              (-> (hotdir-progress-ui-handler request)
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
     ["/proxy/list"
      {:get proxy-list-handler}]
     ["/proxy"
      {:get (fn [request]
              (-> (proxy-page request)
                  (response/response)
                  (response/header "content-type" "text/html")))}]
     ["/proxy/add"
      {:post proxy-add-handler}]
     ["/proxy/remove"
      {:post proxy-remove-handler}]
     ["/proxy/refresh"
      {:post proxy-refresh-handler}]
     ["/proxy/working"
      {:get proxy-working-handler}]
     ["/proxy/ui/add"
      {:post proxy-ui-add-handler}]
     ["/proxy/ui/remove"
      {:post proxy-ui-remove-handler}]
     ["/proxy/ui/refresh"
      {:post proxy-ui-refresh-handler}]
     ["/proxy/ui/panel"
      {:get proxy-ui-panel-handler}]
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
  (proxy/load-proxies!)
  (proxy/ensure-initial-proxies!)
  (proxy/refresh-proxy-status!)
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

  (copy-abris "/home/k0t3sla/TMP/HOT_DIR/CL237B310_31.jpg"))
