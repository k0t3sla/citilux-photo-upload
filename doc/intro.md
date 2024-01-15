# Introduction to citilux-photo-upload

dev 
npx tailwindcss -i ./resources/input.css -o ./resources/public/styles.css --watch

prod
npx tailwindcss -i ./resources/input.css -o ./resources/public/styles.css



beckpup
(defn prepare-to-1c [data]
  (->> data
       (map (fn [[sku amount]]
              {:sku (keyword sku)
               :amount amount}))
       (vec)))