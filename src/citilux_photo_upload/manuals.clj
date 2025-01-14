(ns citilux-photo-upload.manuals
  (:require [clojure.string :as str]
            [hiccup.page :as hiccup]
            [babashka.fs :as fs]
            [citilux-photo-upload.utils :refer [exist? all-articles]]))

(defn manuals-page [_]
  (hiccup/html5
   [:body
    [:head (hiccup/include-css "styles.css" "additional.css")]
    [:head (hiccup/include-js "htmx.js")]
    [:div {:class "container mx-auto p-4"}
     [:div
      [:h2 {:class "text-xl font-semibold mb-2"} "Инструкции"]
      [:form {:method "POST" :action "/upload-instructions" :class "flex flex-col items-center"}
       [:textarea {:class "w-[80vw] h-[23vh] p-4 mb-4 border rounded textarea textarea-primary"
                   :name "content1"
                   :placeholder "Введите содержимое для формы инструкций.\nФормат: CL123456 Путь к файлу"}]
       [:button {:type "submit" :class "btn btn-primary btn-sm w-40"} "Загрузить"]]]

     [:div
      [:h2 {:class "text-xl font-semibold mb-2"} "Схемы сборки"]
      [:form {:method "POST" :action "/upload-manuals" :class "flex flex-col items-center"}
       [:textarea {:class "w-[80vw] h-[23vh] p-4 mb-4 border rounded textarea textarea-secondary"
                   :name "content2"
                   :placeholder "Введите содержимое для формы схем сборки.\nФормат: CL123456 Путь к файлу"}]
       [:button {:type "submit" :class "btn btn-secondary btn-sm w-40"} "Загрузить"]]]

     [:div
      [:h2 {:class "text-xl font-semibold mb-2"} "Коробки"]
      [:form {:method "POST" :action "/upload-boxes" :class "flex flex-col items-center"}
       [:textarea {:class "w-[80vw] h-[23vh] p-4 mb-4 border rounded textarea textarea-info"
                   :name "content3"
                   :placeholder "Введите содержимое для формы коробок.\nФормат: CL123456 Путь к файлу"}]
       [:button {:type "submit" :class "btn btn-info btn-sm w-40"} "Загрузить"]]]]]))

(defn parse-manual-data [content]
  (->> (str/split-lines content)
       (remove str/blank?)
       (map #(str/split % #"[\s\t]+" 2))
       (filter #(= 2 (count %)))
       (map (fn [[article path]]
              {:article (str/trim article)
               :path (str/trim path)}))))

(defn convert-path [windows-path]
  (try
    (-> windows-path
        (str/replace #"\\\\nas\\databank" "/mnt/nas")
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

(defn reports-page [request]
  (let [instructions-data (some-> request :params :content1 parse-manual-data)
        manuals-data (some-> request :params :content2 parse-manual-data)
        boxes-data (some-> request :params :content3 parse-manual-data)

        validated-instructions (when instructions-data (map validate-manual-entry instructions-data))
        validated-manuals (when manuals-data (map validate-manual-entry manuals-data))
        validated-boxes (when boxes-data (map validate-manual-entry boxes-data))]

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

       (when validated-manuals
         [:div {:class "mb-8"}
          [:h2 {:class "text-xl font-semibold mb-4"} "Схемы сборки"]
          [:table {:class "table w-full"}
           [:thead
            [:tr
             [:th "Артикул"]
             [:th "Путь"]
             [:th "Статус"]]]
           [:tbody
            (for [{:keys [article path valid? errors]} validated-manuals]
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

       [:div {:class "flex justify-between mt-8"}
        [:a {:href "/manuals" :class "btn btn-primary"} "Вернуться назад"]
        (when (and (some :valid? validated-instructions)
                   (some :valid? validated-manuals)
                   (some :valid? validated-boxes))
          [:button {:class "btn btn-success"} "Продолжить загрузку"])]]])))