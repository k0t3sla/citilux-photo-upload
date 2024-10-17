(ns citilux-photo-upload.file-checker
  "Namespace for checking and validating file names according to specific patterns."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [config.core :refer [env]]
            [babashka.fs :as fs]
            [citilux-photo-upload.utils :refer [get-article create-path get-all-articles]]))

(def articles (set (get-all-articles)))

(defn valid-article? [article]
  (contains? articles article))

(defn valid-article-prefix? [prefix]
  (some #(str/starts-with? % prefix) articles))

(s/def ::article-part (s/and string? valid-article?))
(s/def ::number-part (s/and string? #(re-matches #"\d+" %)))
(s/def ::extension #{"jpg" "jpeg" "png"})

(s/def ::abris (s/and string? #(re-matches #".*_31\.(jpg|png)$" %)))
(s/def ::white (s/and string? #(re-matches #".*_00\.(jpg|png)$" %)))

(s/def ::file-name
  (s/and
   string?
   #(let [[_ article number ext] (re-matches #"(.+)_(\d+)\.(\w+)" %)]
      (and article number ext
           (s/valid? ::article-part article)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-regular-file-name? [file-name]
  (s/valid? ::file-name (fs/file-name file-name)))

(comment
  (valid-regular-file-name? "CL723330G_31.jpg")
  )


(defn create-file-name-spec
  "Creates a spec for validating file names with a specific suffix."
  [suffix]
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches (re-pattern (str "(.{5}).*_" suffix "_?(\\d+)\\.(\\w+)")) %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn create-valid-file-name-fn
  "Creates a function that validates file names for a specific suffix."
  [suffix]
  (fn [file-name]
    (s/valid? (create-file-name-spec suffix) (fs/file-name file-name))))

(def valid-file-name-SMM? (create-valid-file-name-fn "SMM"))
(def valid-file-name-BANNERS? (create-valid-file-name-fn "BANNERS"))
(def valid-file-name-WEBBANNERS? (create-valid-file-name-fn "WEBBANNERS"))
(def valid-file-name-NEWS? (create-valid-file-name-fn "NEWS"))
(def valid-file-name-MAIL? (create-valid-file-name-fn "MAIL"))
(def valid-file-name-SMM_ALL? (create-valid-file-name-fn "SMM_ALL"))
(def valid-file-name-NEWS_ALL? (create-valid-file-name-fn "NEWS_ALL"))
(def valid-file-name-BANNERS_ALL? (create-valid-file-name-fn "BANNERS_ALL"))
(def valid-file-name-WEBBANNERS_ALL? (create-valid-file-name-fn "WEBBANNERS_ALL"))
(def valid-file-name-MAIL_ALL? (create-valid-file-name-fn "MAIL_ALL"))

(defn check-abris? [file-name]
  (and (s/valid? ::abris file-name)
       (not (fs/exists? (fs/path (:out-path env) 
                                 (create-path (get-article (fs/file-name file-name))) 
                                 (fs/file-name file-name))))))  

(defn white? [file-name]
  (s/valid? ::white (fs/file-name file-name)))



(comment
  (str (:out-path env) (create-path (get-article "CL723330G_1.jpg")) "CL723330G_1.jpg")
  (valid-regular-file-name? "CL723330G_1.jpg")
  (valid-regular-file-name? "107.97.1_2.png")
  (valid-file-name-MAIL_ALL? "CL723_MAIL_ALL_1.jpg")
  (valid-file-name-SMM_ALL? "CL723_SMM_ALL_1.jpg")
  (valid-file-name-SMM_ALL? "CL723_SMM_ALL_.jpg")
  (valid-file-name-SMM_ALL? "not-valid_SMM_ALL_1.jpg")
  (valid-regular-file-name? "INVALID_1.jpg")
  (valid-regular-file-name? "CL3423434_A.jpg")
  (valid-regular-file-name? "CL3423434_1.gif"))
