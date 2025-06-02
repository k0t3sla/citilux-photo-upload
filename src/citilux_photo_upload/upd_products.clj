(ns citilux-photo-upload.upd-products
  (:require
   [citilux-photo-upload.utils :refer [all-articles
                                       exist? split-articles]]
   [clojure.string :as str]
   [config.core :refer [env]]
   [hiccup.page :as hiccup]
   [clj-http.client :as client]
   [cheshire.core :refer [parse-string]]))

(defn upd-products-page [_]
  (hiccup/html5
   [:body
    [:head (hiccup/include-css "styles.css" "additional.css")]
    [:head (hiccup/include-js "htmx.js")]
    [:div {:class "container mx-auto p-4"}
     [:form {:method "POST" :action "/upd-products" :class "flex flex-col items-center"}
      [:div
       [:h2 {:class "text-xl font-semibold mb-2"} "Обновление продуктов"]
       [:textarea {:class "w-[80vw] h-[23vh] p-4 mb-4 border rounded textarea textarea-primary"
                  :name "products"
                  :placeholder "Введите артикулы для обновления.\nФормат: CL123456, CL703 или через перенос строки\nПример: CL714A40G (точный), CL703 (по подстроке)\n\nТребования: больше 5 символов и должны существовать артикулы, начинающиеся с введенной подстроки"}]] 
      
      [:button {:type "submit" :class "btn btn-primary btn-sm w-40"} "Обновить продукты"]]]]))

(defn filter-articles 
  "Фильтрует артикулы на корректные и некорректные"
  [{:keys [filter-errors? articles]}] 
  (let [check-article (fn [article]
                        (if (<= (count article) 4)
                          ;; Для коротких артикулов (4 символов и меньше) считаем невалидными
                          false
                          ;; Для длинных артикулов проверяем есть ли артикулы, начинающиеся с введенной подстроки
                          (some #(str/starts-with? % article) @all-articles)))
        correct-arts (vec (filter check-article articles))
        not-correct-arts (vec (remove check-article articles))]
    (if filter-errors?
      not-correct-arts
      correct-arts)))

(defn parse-articles
  "Парсит строку с артикулами, разделенными запятыми, пробелами или переносами строк"
  [content]
  (->> content
       split-articles
       (map str/trim)
       (remove str/blank?)
       vec))

(defn update-product-on-server
  "Отправляет запрос на обновление одного продукта"
  [article] 
  (try
    (let [response (client/post (str (:test-site env) "/api/update-products")
                                {:headers {:authorization (str "Bearer " (:test-site-token env))}
                                 :content-type :json
                                 :form-params {:article article}
                                 :throw-exceptions false})]
      (if (= 200 (:status response))
        (parse-string (:body response) true)
        {:errors [{:article article :reason (str "HTTP Error: " (:status response))}]}))
    (catch Exception e
      {:errors [{:article article :reason (str "Exception: " (.getMessage e))}]})))

(defn update-products-batch
  "Обновляет список продуктов и собирает результаты"
  [articles] 
  (let [results (map update-product-on-server articles)]
    (reduce (fn [acc result]
              {:updated (concat (:updated acc) (:updated result))
               :errors (concat (:errors acc) (:errors result))})
            {:updated [] :errors []}
            results)))

(defn display-results 
  "Отображает результаты обновления продуктов"
  [results _ error-articles]
  [:div {:class "container mx-auto p-4"}
   [:h2 {:class "text-xl font-semibold mb-4"} "Результаты обновления продуктов"]
   
   ;; Показываем некорректные артикулы (не найдены в базе)
   (when (not-empty error-articles)
     [:div {:class "mb-4 p-4 bg-red-100 border border-red-300 rounded"}
      [:h3 {:class "text-lg font-semibold text-red-700 mb-2"} "Артикулы с ошибками валидации:"]
      [:ul {:class "list-disc list-inside text-red-600"}
       (for [article error-articles]
         (let [reason (if (<= (count article) 4)
                        "слишком короткий (нужно больше 4 символов)"
                        "не найдено артикулов, начинающихся с этой подстроки")]
           [:li article " - " reason]))]])
   
   ;; Показываем успешно обновленные
   (when (not-empty (:updated results))
     [:div {:class "mb-4 p-4 bg-green-100 border border-green-300 rounded"}
      [:h3 {:class "text-lg font-semibold text-green-700 mb-2"} "Успешно обновлены:"]
      [:ul {:class "list-disc list-inside text-green-600"}
       (for [article (:updated results)]
         [:li article])]])
   
   ;; Показываем ошибки сервера
   (when (not-empty (:errors results))
     [:div {:class "mb-4 p-4 bg-yellow-100 border border-yellow-300 rounded"}
      [:h3 {:class "text-lg font-semibold text-yellow-700 mb-2"} "Ошибки сервера:"]
      [:ul {:class "list-disc list-inside text-yellow-600"}
       (for [error (:errors results)]
         [:li (:article error) " - " (:reason error)])]])
   
   [:div {:class "flex justify-center mt-6"}
    [:a {:href "/upd-products" :class "btn btn-primary"} "Вернуться к форме"]]])

(defn upd-products [request]
  (try
    (let [products-content (get-in request [:params :products])
          _ (println "Получен контент:" products-content)
          
          articles (parse-articles products-content)
          _ (println "Распарсенные артикулы:" articles)
          
          error-articles (filter-articles {:filter-errors? true :articles articles})
          correct-articles (filter-articles {:filter-errors? false :articles articles})
          _ (println "Корректные артикулы:" correct-articles)
          _ (println "Некорректные артикулы:" error-articles)]
      
      (if (empty? correct-articles)
        ;; Если нет корректных артикулов
        (hiccup/html5
         [:body
          [:head (hiccup/include-css "styles.css" "additional.css")]
          (display-results {:updated [] :errors []} [] error-articles)])
        
        ;; Обновляем корректные артикулы
        (let [results (update-products-batch correct-articles)
              _ (println "Результаты обновления:" results)]
          
          (hiccup/html5
           [:body
            [:head (hiccup/include-css "styles.css" "additional.css")]
            (display-results results correct-articles error-articles)]))))
    
    (catch Exception e
      (println "Ошибка в upd-products:" (.getMessage e))
      (hiccup/html5
       [:body
        [:head (hiccup/include-css "styles.css" "additional.css")]
        [:div {:class "container mx-auto p-4"}
         [:div {:class "p-4 bg-red-100 border border-red-300 rounded"}
          [:h2 {:class "text-lg font-semibold text-red-700"} "Произошла ошибка"]
          [:p {:class "text-red-600"} (.getMessage e)]
          [:div {:class "mt-4"}
           [:a {:href "/upd-products" :class "btn btn-primary"} "Вернуться к форме"]]]]]))))