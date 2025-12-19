(ns citilux-photo-upload.manuals
  (:require
   [babashka.fs :as fs]
   [citilux-photo-upload.upload :refer [upload-manuals]]
   [citilux-photo-upload.utils :refer [all-articles create-path-with-root
                                       exist?]]
   [clojure.string :as str]
   [config.core :refer [env]]
   [hiccup.page :as hiccup]))

(defn manuals-page [_]
  (hiccup/html5
   [:body
    [:head (hiccup/include-css "styles.css" "additional.css")]
    [:head (hiccup/include-js "htmx.js")]
    [:div {:class "container mx-auto p-4"}
     [:form {:method "POST" :action "/upload-instructions" :class "flex flex-col items-center"}
      [:div
       [:h2 {:class "text-xl font-semibold mb-2"} "Инструкции"]
       [:textarea {:class "w-[80vw] h-[23vh] p-4 mb-4 border rounded textarea textarea-primary"
                  :name "instructions"
                  :placeholder "Введите содержимое для формы инструкций.\nФормат: CL123456 Путь к файлу"}]]

      [:div
       [:h2 {:class "text-xl font-semibold mb-2"} "Схемы сборки"]
       [:textarea {:class "w-[80vw] h-[23vh] p-4 mb-4 border rounded textarea textarea-secondary"
                  :name "assembly"
                  :placeholder "Введите содержимое для формы схем сборки.\nФормат: CL123456 Путь к файлу"}]]

      [:div
       [:h2 {:class "text-xl font-semibold mb-2"} "Коробки"]
       [:textarea {:class "w-[80vw] h-[23vh] p-4 mb-4 border rounded textarea textarea-info"
                  :name "boxes"
                  :placeholder "Введите содержимое для формы коробок.\nФормат: CL123456 Путь к файлу"}]]
      
      [:button {:type "submit" :class "btn btn-primary btn-sm w-40"} "Загрузить все"]]]]))

(defn parse-manual-data [content]
  (->> (str/split-lines content)
       (remove str/blank?)
       (map #(str/split % #"[\s\t]+" 2))
       (filter #(= 2 (count %)))
       (map (fn [[article path]]
              {:article (str/trim article)
               :path (str/trim path)}))))

(defn copy-manuals 
  "Копируем файлы на файловую систему
   :out-path - потом бренд 01_PRODUCTION_FILES\\03_MANUAL"
  [art path-to-file path-to-save]
  (let [out-path (str (:out-path env) (create-path-with-root art path-to-save) (fs/file-name path-to-file))
        parent-dir (fs/parent out-path)]
    (println "copying manuals" art path-to-file out-path)
    (fs/create-dirs parent-dir)  ; Создаём все необходимые директории
    (fs/copy path-to-file out-path {:replace-existing true})))


(defn convert-path [windows-path]
  (try
    (-> windows-path
        (str/replace #"\\\\diskstation\\CATALOG" "/mnt/catalog")
        (str/replace #"\\" "/"))
    (catch Exception _
      nil)))

(defn validate-manual-entry [{:keys [article path]}]
  (let [linux-path (convert-path path)
        article-exists? (exist? article @all-articles)
        path-converted? (some? linux-path)
        file-exists? (and path-converted? (fs/exists? linux-path))]
    {:article article
     :original-path path
     :path (or linux-path path)
     :valid? (and article-exists? file-exists? path-converted?)
     :errors (cond-> []
               (not article-exists?) (conj "Артикул не существует")
               (not path-converted?) (conj "Некорректный формат пути")
               (and path-converted? (not file-exists?)) (conj "Файл не найден"))}))

(defn create-upload-status-map
  "Создает map для быстрого поиска статуса загрузки по артикулу и типу"
  [upload-result]
  (let [success-map (->> (:success upload-result)
                         (group-by :art)
                         (map (fn [[art items]]
                                [art (into {} (map (fn [item] [(:type item) item]) items))]))
                         (into {}))
        errors-map (->> (:errors upload-result)
                        (filter map?)
                        (group-by :art)
                        (map (fn [[art items]]
                               [art (into {} (map (fn [item] [(:type item) item]) items))]))
                        (into {}))
        string-errors (filter string? (:errors upload-result))]
    {:success success-map
     :errors errors-map
     :string-errors string-errors}))

(defn get-upload-status
  "Получает статус загрузки для конкретного артикула и типа"
  [status-map article type]
  (cond
    (get-in status-map [:success article type]) 
    {:status :success :data (get-in status-map [:success article type])}
    (get-in status-map [:errors article type])
    {:status :error :data (get-in status-map [:errors article type])}
    :else nil))

(defn upload-instructions [request]
  (let [instructions-data (some-> request :params :instructions parse-manual-data)
        assembly-data (some-> request :params :assembly parse-manual-data)
        boxes-data (some-> request :params :boxes parse-manual-data)

        validated-instructions (when instructions-data (map validate-manual-entry instructions-data))
        validated-assembly (when assembly-data (map validate-manual-entry assembly-data))
        validated-boxes (when boxes-data (map validate-manual-entry boxes-data))
        
        valid-instructions (filter :valid? validated-instructions)
        valid-assembly (filter :valid? validated-assembly)
        valid-boxes (filter :valid? validated-boxes)

        upload-result (when (or (seq valid-instructions) (seq valid-assembly) (seq valid-boxes))
                       ;; Копируем файлы на файловую систему
                       (when (seq valid-instructions)
                         (doseq [{:keys [article path]} valid-instructions]
                           (copy-manuals article path "01_PRODUCTION_FILES/03_MANUAL/")))
                       (when (seq valid-assembly)
                         (doseq [{:keys [article path]} valid-assembly]
                           (copy-manuals article path "01_PRODUCTION_FILES/03_ASSEMBLY/")))
                       (when (seq valid-boxes)
                         (doseq [{:keys [article path]} valid-boxes]
                           (copy-manuals article path "01_PRODUCTION_FILES/02_BOX/")))
                       
                       ;; Загружаем на сервер и получаем результат
                       (upload-manuals valid-instructions valid-assembly))
        
        upload-status (when upload-result (create-upload-status-map upload-result))]

    (hiccup/html5
     [:body
      [:head (hiccup/include-css "styles.css" "additional.css")]
      [:head (hiccup/include-js "htmx.js")]
      [:div {:class "container mx-auto p-4"}
       [:h1 {:class "text-2xl font-bold mb-8"} "Загруженные данные"]

       ;; Общие ошибки (строковые)
       (when (and upload-status (seq (:string-errors upload-status)))
         [:div {:class "mb-8 p-4 bg-red-100 border border-red-400 rounded"}
          [:h2 {:class "text-xl font-semibold mb-2 text-red-800"} "Общие ошибки"]
          [:ul {:class "list-disc list-inside"}
           (for [error (:string-errors upload-status)]
             [:li {:class "text-red-600"} error])]])

       (when validated-instructions
         [:div {:class "mb-8"}
          [:h2 {:class "text-xl font-semibold mb-4"} "Инструкции"]
          [:table {:class "table w-full"}
           [:thead
            [:tr
             [:th "Артикул"]
             [:th "Путь"]
             [:th "Валидация"]
             [:th "Загрузка на сервер"]]]
           [:tbody
            (for [{:keys [article path valid? errors]} validated-instructions]
              (let [upload-status-item (when upload-status (get-upload-status upload-status article "instruction"))]
                [:tr {:class (cond
                               (and valid? (= (:status upload-status-item) :success)) "bg-green-100"
                               (and valid? (= (:status upload-status-item) :error)) "bg-yellow-100"
                               valid? "bg-blue-100"
                               :else "bg-red-100")}
                 [:td article]
                 [:td path]
                 [:td (if valid?
                        [:span {:class "text-green-600"} "✓ Валидно"]
                        [:div {:class "text-red-600"}
                         (str/join ", " errors)])]
                 [:td (cond
                        (nil? upload-status-item)
                        (if valid?
                          [:span {:class "text-gray-500"} "Ожидание загрузки"]
                          [:span {:class "text-gray-400"} "-"])
                        (= (:status upload-status-item) :success)
                        [:div {:class "text-green-600"}
                         [:span "✓ Загружено"]
                         (when-let [file-path (:file_path (:data upload-status-item))]
                           [:div {:class "text-xs text-gray-600 mt-1"} file-path])]
                        (= (:status upload-status-item) :error)
                        [:div {:class "text-red-600"}
                         [:span "✗ Ошибка"]
                         (when-let [error-msg (:error (:data upload-status-item))]
                           [:div {:class "text-xs mt-1"} error-msg])]
                        :else
                        [:span {:class "text-gray-500"} "Неизвестный статус"])]]))]]])

       (when validated-assembly
         [:div {:class "mb-8"}
          [:h2 {:class "text-xl font-semibold mb-4"} "Схемы сборки"]
          [:table {:class "table w-full"}
           [:thead
            [:tr
             [:th "Артикул"]
             [:th "Путь"]
             [:th "Валидация"]
             [:th "Загрузка на сервер"]]]
           [:tbody
            (for [{:keys [article path valid? errors]} validated-assembly]
              (let [upload-status-item (when upload-status (get-upload-status upload-status article "assembly"))]
                [:tr {:class (cond
                               (and valid? (= (:status upload-status-item) :success)) "bg-green-100"
                               (and valid? (= (:status upload-status-item) :error)) "bg-yellow-100"
                               valid? "bg-blue-100"
                               :else "bg-red-100")}
                 [:td article]
                 [:td path]
                 [:td (if valid?
                        [:span {:class "text-green-600"} "✓ Валидно"]
                        [:div {:class "text-red-600"}
                         (str/join ", " errors)])]
                 [:td (cond
                        (nil? upload-status-item)
                        (if valid?
                          [:span {:class "text-gray-500"} "Ожидание загрузки"]
                          [:span {:class "text-gray-400"} "-"])
                        (= (:status upload-status-item) :success)
                        [:div {:class "text-green-600"}
                         [:span "✓ Загружено"]
                         (when-let [file-path (:file_path (:data upload-status-item))]
                           [:div {:class "text-xs text-gray-600 mt-1"} file-path])]
                        (= (:status upload-status-item) :error)
                        [:div {:class "text-red-600"}
                         [:span "✗ Ошибка"]
                         (when-let [error-msg (:error (:data upload-status-item))]
                           [:div {:class "text-xs mt-1"} error-msg])]
                        :else
                        [:span {:class "text-gray-500"} "Неизвестный статус"])]]))]]])

       (when validated-boxes
         [:div {:class "mb-8"}
          [:h2 {:class "text-xl font-semibold mb-4"} "Коробки"]
          [:table {:class "table w-full"}
           [:thead
            [:tr
             [:th "Артикул"]
             [:th "Путь"]
             [:th "Статус"]]]
           [:tbody
            (for [{:keys [article path valid? errors]} validated-boxes]
              [:tr {:class (if valid? "bg-green-100" "bg-red-100")}
               [:td article]
               [:td path]
               [:td (if valid?
                      [:span {:class "text-green-600"} "✓ Валидно (скопировано локально)"]
                      [:div {:class "text-red-600"}
                       (str/join ", " errors)])]])]]]) 

       [:div {:class "flex justify-between mt-8"}
        [:a {:href "/manuals" :class "btn btn-primary"} "Вернуться назад"]
        (when (and (some :valid? validated-instructions)
                   (some :valid? validated-assembly)
                   (some :valid? validated-boxes))
          [:button {:class "btn btn-success"} "Продолжить загрузку"])]]])))