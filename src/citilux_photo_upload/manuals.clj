(ns citilux-photo-upload.manuals
  (:require [clojure.string :as str]
            [hiccup.page :as hiccup]
            [babashka.fs :as fs]
            [citilux-photo-upload.upload :refer [upload-manuals]]
            [citilux-photo-upload.utils :refer [exist? all-articles create-path-with-root]]))

;; Объявляем атом на уровне пространства имен
(def upload-response (atom nil))

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
  (let [out-path (create-path-with-root art path-to-save)
        parent-dir (fs/parent out-path)]
    (println "copying manuals" art path-to-file path-to-save)
    (fs/create-dirs parent-dir)  ; Создаём все необходимые директории
    (fs/copy path-to-file out-path {:replace-existing true})))


(defn convert-path [windows-path]
  (try
    (-> windows-path
        (str/replace #"\\diskstation\CATALOG" "/mnt/nas")
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

(defn upload-instructions [request]
  (let [instructions-data (some-> request :params :instructions parse-manual-data)
        assembly-data (some-> request :params :assembly parse-manual-data)
        boxes-data (some-> request :params :boxes parse-manual-data)

        validated-instructions (when instructions-data (map validate-manual-entry instructions-data))
        validated-assembly (when assembly-data (map validate-manual-entry assembly-data))
        validated-boxes (when boxes-data (map validate-manual-entry boxes-data))
        
        valid-instructions (filter :valid? validated-instructions)
        valid-assembly (filter :valid? validated-assembly)
        valid-boxes (filter :valid? validated-boxes)]

    
    ;; Если есть валидные данные, копируем файлы и отправляем на сервер
    (when (or (seq valid-instructions) (seq valid-assembly) (seq valid-boxes))
      (when (seq valid-instructions)
        (doseq [{:keys [article path]} valid-instructions]
          (copy-manuals article path "01_PRODUCTION_FILES/03_MANUAL")))
      (when (seq valid-assembly)
        (doseq [{:keys [article path]} valid-assembly]
          (copy-manuals article path "01_PRODUCTION_FILES/03_ASSEMBLY")))
      (when (seq valid-boxes)
        (doseq [{:keys [article path]} valid-boxes]
          (copy-manuals article path "01_PRODUCTION_FILES/02_BOX")))
      
      ;; Сохраняем ответ в атом
      (reset! upload-response (upload-manuals valid-instructions valid-assembly)))

    (hiccup/html5
     [:body
      [:head (hiccup/include-css "styles.css" "additional.css")]
      [:head (hiccup/include-js "htmx.js")]
      [:div {:class "container mx-auto p-4"}
       [:h1 {:class "text-2xl font-bold mb-8"} "Загруженные данные"]

       (when validated-instructions
         [:div {:class "mb-8"}
          [:h2 {:class "text-xl font-semibold mb-4"} "Инструкции"]
          [:table {:class "table w-full"}
           [:thead
            [:tr
             [:th "Артикул"]
             [:th "Путь"]
             [:th "Статус"]]]
           [:tbody
            (for [{:keys [article path valid? errors]} validated-instructions]
              [:tr {:class (if valid? "bg-green-100" "bg-red-100")}
               [:td article]
               [:td path]
               [:td (if valid?
                      [:span {:class "text-green-600"} "✓ Валидно"]
                      [:div {:class "text-red-600"}
                       (str/join ", " errors)])]])]]])

       (when validated-assembly
         [:div {:class "mb-8"}
          [:h2 {:class "text-xl font-semibold mb-4"} "Схемы сборки"]
          [:table {:class "table w-full"}
           [:thead
            [:tr
             [:th "Артикул"]
             [:th "Путь"]
             [:th "Статус"]]]
           [:tbody
            (for [{:keys [article path valid? errors]} validated-assembly]
              [:tr {:class (if valid? "bg-green-100" "bg-red-100")}
               [:td article]
               [:td path]
               [:td (if valid?
                      [:span {:class "text-green-600"} "✓ Валидно"]
                      [:div {:class "text-red-600"}
                       (str/join ", " errors)])]])]]])

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
                      [:span {:class "text-green-600"} "✓ Валидно"]
                      [:div {:class "text-red-600"}
                       (str/join ", " errors)])]])]]])
       
       [:div {:class "response-debug mt-4 p-4 bg-gray-100 rounded"}
        [:h3 {:class "text-lg font-semibold mb-2"} "Ответ сервера:"]
        [:pre {:class "whitespace-pre-wrap"} (when @upload-response (pr-str @upload-response))]
        ;; Очищаем ответ после отображения
        (when @upload-response (reset! upload-response nil))]

       [:div {:class "flex justify-between mt-8"}
        [:a {:href "/manuals" :class "btn btn-primary"} "Вернуться назад"]
        (when (and (some :valid? validated-instructions)
                   (some :valid? validated-assembly)
                   (some :valid? validated-boxes))
          [:button {:class "btn btn-success"} "Продолжить загрузку"])]]])))