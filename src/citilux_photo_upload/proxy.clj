(ns citilux-photo-upload.proxy
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [config.core :refer [env]])
  (:import [java.net URI URLDecoder InetSocketAddress Socket]
           [java.time Instant]))

(def default-proxy-links
  ["https://t.me/socks?server=111.88.107.106&port=1080&user=proxylux&pass=geipiem7Feedai4e"
   "https://t.me/proxy?server=146.185.209.244&port=443&secret=9f3c7a8d2e4b1c6f5a7d8e9b0c2f4a1d"
   "https://t.me/proxy?server=146.185.242.117&port=443&secret=9f3c7a8d2e4b1c6f5a7d8e9b0c2f4a1d"
   "https://t.me/proxy?server=37.139.34.153&port=443&secret=9f3c7a8d2e4b1c6f5a7d8e9b0c2f4a1d"
   "https://t.me/proxy?server=213.219.212.245&port=443&secret=9f3c7a8d2e4b1c6f5a7d8e9b0c2f4a1d"
   "https://t.me/proxy?server=37.139.32.18&port=443&secret=9f3c7a8d2e4b1c6f5a7d8e9b0c2f4a1d"])

(defonce proxy-state
  (atom {:proxies []}))

(defn- now-iso []
  (str (Instant/now)))

(defn- storage-path []
  (or (:proxy-storage-path env) "data/proxies.edn"))

(defn- parse-query-string [q]
  (if (str/blank? q)
    {}
    (->> (str/split q #"&")
         (map #(str/split % #"=" 2))
         (map (fn [[k v]]
                [(keyword (URLDecoder/decode (or k "") "UTF-8"))
                 (URLDecoder/decode (or v "") "UTF-8")]))
         (into {}))))

(defn- parse-port [port-str]
  (try
    (Integer/parseInt (str port-str))
    (catch Exception _ nil)))

(defn parse-proxy-link [raw]
  (try
    (let [raw (str/trim (str raw))
          uri (URI. raw)
          path (or (.getPath uri) "")
          params (parse-query-string (.getRawQuery uri))
          server (:server params)
          port (parse-port (:port params))
          type (cond
                 (str/ends-with? path "/socks") :socks
                 (str/ends-with? path "/proxy") :mtproxy
                 :else nil)
          id (str (name type) ":" server ":" port)]
      (when (and type (not (str/blank? server)) (integer? port))
        {:id id
         :type type
         :server server
         :port port
         :user (:user params)
         :pass (:pass params)
         :secret (:secret params)
         :request-proxy (str server ":" port)
         :raw-url raw
         :created-at (now-iso)
         :alive? nil
         :latency-ms nil
         :last-check-at nil
         :last-error nil}))
    (catch Exception _ nil)))

(defn- ensure-storage-parent! [path]
  (let [parent (fs/parent path)]
    (when parent (fs/create-dirs parent))))

(defn save-proxies! []
  (let [path (storage-path)]
    (ensure-storage-parent! path)
    (spit path (pr-str (:proxies @proxy-state)))))

(defn load-proxies! []
  (let [path (storage-path)]
    (if (fs/exists? path)
      (let [data (edn/read-string (slurp path))]
        (swap! proxy-state assoc :proxies (vec data)))
      (do
        (swap! proxy-state assoc :proxies [])
        nil))))

(defn list-proxies []
  (:proxies @proxy-state))

(defn- merge-proxy [existing incoming]
  (merge existing (dissoc incoming :alive? :latency-ms :last-check-at :last-error :created-at)))

(defn add-proxies! [items]
  (let [raw-items (cond
                    (string? items) (->> (str/split-lines items) (map str/trim) (remove str/blank?))
                    (sequential? items) (map str items)
                    :else [])
        parsed (keep parse-proxy-link raw-items)
        invalid-count (- (count raw-items) (count parsed))]
    (swap! proxy-state
           (fn [{:keys [proxies] :as state}]
             (let [by-id (into {} (map (juxt :id identity) proxies))
                   merged (reduce (fn [acc p]
                                    (assoc acc (:id p) (if-let [old (get acc (:id p))]
                                                         (merge-proxy old p)
                                                         p)))
                                  by-id
                                  parsed)]
               (assoc state :proxies (vec (vals merged))))))
    (save-proxies!)
    {:added (count parsed)
     :invalid invalid-count
     :total (count (:proxies @proxy-state))}))

(defn remove-proxies! [ids-or-urls]
  (let [to-remove (set (map str ids-or-urls))]
    (swap! proxy-state update :proxies
           (fn [proxies]
             (vec (remove (fn [{:keys [id raw-url]}]
                            (or (contains? to-remove id)
                                (contains? to-remove raw-url)))
                          proxies))))
    (save-proxies!)
    {:total (count (:proxies @proxy-state))}))

(defn ping-proxy [proxy]
  (let [start (System/nanoTime)
        socket (Socket.)]
    (try
      (.connect socket (InetSocketAddress. ^String (:server proxy) ^Integer (:port proxy)) 2000)
      (let [latency (long (/ (- (System/nanoTime) start) 1000000))]
        (assoc proxy
               :alive? true
               :latency-ms latency
               :last-check-at (now-iso)
               :last-error nil))
      (catch Exception e
        (assoc proxy
               :alive? false
               :latency-ms nil
               :last-check-at (now-iso)
               :last-error (.getMessage e)))
      (finally
        (try (.close socket) (catch Exception _ nil))))))

(defn refresh-proxy-status! []
  (swap! proxy-state update :proxies
         (fn [proxies]
           (mapv ping-proxy proxies)))
  (save-proxies!)
  (:proxies @proxy-state))

(defn working-proxies []
  (filterv :alive? (:proxies @proxy-state)))

(defn get-working-proxy-url []
  (or (:raw-url (first (working-proxies)))
      (some-> (first (:proxies @proxy-state)) :raw-url)))

(defn candidate-request-proxies []
  (->> (:proxies @proxy-state)
       (sort-by (fn [p] [(not (:alive? p)) (or (:latency-ms p) 999999)]))
       (mapv :request-proxy)
       distinct))

(defn any-proxy-alive? []
  (boolean (seq (working-proxies))))

(defn ensure-initial-proxies! []
  (when (empty? (:proxies @proxy-state))
    (add-proxies! default-proxy-links)))
