(ns citilux-photo-upload.file-checker
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


(s/def ::file-name_SMM
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches #"(.{5}).*_SMM_(\d+)\.(\w+)" %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-file-name-SMM? [file-name]
  (s/valid? ::file-name_SMM (fs/file-name file-name)))

(s/def ::file-name_BANNERS
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches #"(.{5}).*_BANNERS_(\d+)\.(\w+)" %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-file-name-BANNERS? [file-name]
  (s/valid? ::file-name_BANNERS (fs/file-name file-name)))

(s/def ::file-name_WEBBANNERS
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches #"(.{5}).*_WEBBANNERS_(\d+)\.(\w+)" %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-file-name-WEBBANNERS? [file-name]
  (s/valid? ::file-name_WEBBANNERS (fs/file-name file-name)))

(s/def ::file-name_NEWS
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches #"(.{5}).*_NEWS_(\d+)\.(\w+)" %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-file-name-NEWS? [file-name]
  (s/valid? ::file-name_NEWS (fs/file-name file-name)))

(s/def ::file-name_MAIL
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches #"(.{5}).*_MAIL_(\d+)\.(\w+)" %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-file-name-MAIL? [file-name]
  (s/valid? ::file-name_MAIL (fs/file-name file-name)))

(s/def ::file-name_SMM_ALL
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches #"(.{5}).*_SMM_ALL_(\d+)\.(\w+)" %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-file-name-SMM_ALL? [file-name]
  (s/valid? ::file-name_SMM_ALL (fs/file-name file-name)))

(s/def ::file-name_NEWS_ALL
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches #"(.{5}).*_NEWS_ALL_(\d+)\.(\w+)" %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-file-name-NEWS_ALL? [file-name]
  (s/valid? ::file-name_NEWS_ALL (fs/file-name file-name)))

(s/def ::file-name_BANNERS_ALL
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches #"(.{5}).*_BANNERS_ALL_(\d+)\.(\w+)" %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-file-name-BANNERS_ALL? [file-name]
  (s/valid? ::file-name_BANNERS_ALL (fs/file-name file-name)))

(s/def ::file-name_WEBBANNERS_ALL
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches #"(.{5}).*_WEBBANNERS_ALL_(\d+)\.(\w+)" %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-file-name-WEBBANNERS_ALL? [file-name]
  (s/valid? ::file-name_WEBBANNERS_ALL (fs/file-name file-name)))

(s/def ::file-name_MAIL_ALL
  (s/and
   string?
   #(let [[_ prefix number ext] (re-matches #"(.{5}).*_MAIL_ALL_(\d+)\.(\w+)" %)]
      (and prefix number ext
           (valid-article-prefix? prefix)
           (s/valid? ::number-part number)
           (s/valid? ::extension ext)))))

(defn valid-file-name-MAIL_ALL? [file-name]
  (s/valid? ::file-name_MAIL_ALL (fs/file-name file-name)))

(defn check-abris? [file-name]
  (and (s/valid? ::abris file-name)
       (not (fs/exists? (str (:out-path env) (create-path (get-article (fs/file-name file-name))) (fs/file-name file-name))))))  

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