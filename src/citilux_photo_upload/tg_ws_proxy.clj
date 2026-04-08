(ns citilux-photo-upload.tg-ws-proxy
  "Clojure implementation of Telegram MTProto WebSocket proxy.
   Provides WebSocket connection to Telegram servers with message sending/receiving capabilities."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import [java.io InputStream OutputStream]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [java.security.cert X509Certificate]
           [javax.net.ssl SSLContext TrustManager X509TrustManager]
           [javax.crypto Cipher]
           [javax.crypto.spec IvParameterSpec SecretKeySpec]
           [java.nio.charset StandardCharsets]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const dc-default-ips
  {1 "149.154.175.50"
   2 "149.154.167.51"
   3 "149.154.175.100"
   4 "149.154.167.91"
   5 "149.154.171.5"
   203 "91.105.192.100"})

(def ^:const handshake-len 64)
(def ^:const skip-len 8)
(def ^:const prekey-len 32)
(def ^:const key-len 32)
(def ^:const iv-len 16)
(def ^:const proto-tag-pos 56)
(def ^:const dc-idx-pos 60)

(def proto-tag-abridged (byte-array [(unchecked-byte 0xEF) (unchecked-byte 0xEF)
                                     (unchecked-byte 0xEF) (unchecked-byte 0xEF)]))
(def proto-tag-intermediate (byte-array [(unchecked-byte 0xEE) (unchecked-byte 0xEE)
                                         (unchecked-byte 0xEE) (unchecked-byte 0xEE)]))
(def proto-tag-secure (byte-array [(unchecked-byte 0xDD) (unchecked-byte 0xDD)
                                   (unchecked-byte 0xDD) (unchecked-byte 0xDD)]))

(def proto-abridged-int 0xEFEFEFEF)
(def proto-intermediate-int 0xEEEEEEEE)
(def proto-padded-intermediate-int 0xDDDDDDDD)

(def reserved-first-byts #{0xEF})
(def reserved-starts #{(byte-array [(byte 0x48) (byte 0x45) (byte 0x41) (byte 0x44)]) ; HEAD
                       (byte-array [(byte 0x50) (byte 0x4F) (byte 0x53) (byte 0x54)]) ; POST
                       (byte-array [(byte 0x47) (byte 0x45) (byte 0x54) (byte 0x20)]) ; GET 
                       proto-tag-intermediate
                       proto-tag-secure
                       (byte-array [(byte 0x16) (byte 0x03) (byte 0x01) (byte 0x02)])})
(def reserved-continue (byte-array [(byte 0x00) (byte 0x00) (byte 0x00) (byte 0x00)]))

(def zero-64 (byte-array 64))

;; WebSocket opcodes
(def ^:const ws-op-binary 0x2)
(def ^:const ws-op-close 0x8)
(def ^:const ws-op-ping 0x9)
(def ^:const ws-op-pong 0xA)

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn bytes=
  "Compare two byte arrays for equality."
  [^bytes a ^bytes b]
  (java.util.Arrays/equals a b))

(defn secure-random-bytes
  "Generate n random bytes."
  [n]
  (let [rnd (SecureRandom.)
        arr (byte-array n)]
    (.nextBytes rnd arr)
    arr))

(defn sha256
  "Compute SHA-256 hash of byte array."
  [^bytes data]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.digest md data)))

(defn base64-encode
  "Encode byte array to base64 string."
  [^bytes data]
  (.encodeToString (Base64/getEncoder) data))

(defn hex->bytes
  "Convert hex string to byte array."
  [^String hex]
  (let [len (count hex)
        arr (byte-array (/ len 2))]
    (loop [i 0]
      (if (< i len)
        (do
          (aset arr (int (/ i 2))
                (unchecked-byte (Integer/parseInt (subs hex i (+ i 2)) 16)))
          (recur (+ i 2)))
        arr))))

(defn bytes->hex
  "Convert byte array to hex string."
  [^bytes data]
  (apply str (map #(format "%02x" %) data)))

(defn xor-mask
  "XOR data with mask (both byte arrays)."
  [^bytes data ^bytes mask]
  (let [n (alength data)
        result (byte-array n)]
    (loop [i 0]
      (if (< i n)
        (do
          (aset result i (unchecked-byte (bit-xor (aget data i) (aget mask (rem i (alength mask))))))
          (recur (inc i)))
        result))))

(defn bytes->int-le
  "Convert bytes to integer (little-endian, signed)."
  [^bytes data]
  (let [n (alength data)]
    (loop [i 0 val 0]
      (if (< i n)
        (recur (inc i) (bit-or val (bit-shift-left (bit-and (aget data i) 0xFF) (* i 8))))
        (if (>= (bit-and val 0x80000000) 0x80000000)
          (- val 0x100000000)
          val)))))

(defn bytes->int-be
  "Convert bytes to integer (big-endian, unsigned)."
  [^bytes data]
  (let [n (alength data)]
    (loop [i 0 val 0]
      (if (< i n)
        (recur (inc i) (bit-or val (bit-shift-left (bit-and (aget data i) 0xFF) (* (- n 1 i) 8))))
        val))))

(defn int->bytes-le
  "Convert integer to bytes (little-endian)."
  [val nbytes]
  (let [arr (byte-array nbytes)]
    (loop [i 0 v val]
      (if (< i nbytes)
        (do
          (aset arr i (unchecked-byte (bit-and v 0xFF)))
          (recur (inc i) (unsigned-bit-shift-right v 8)))
        arr))))

(defn int->bytes-be
  "Convert integer to bytes (big-endian)."
  [val nbytes]
  (let [arr (byte-array nbytes)]
    (loop [i 0 v val]
      (if (< i nbytes)
        (do
          (aset arr (- nbytes 1 i) (unchecked-byte (bit-and v 0xFF)))
          (recur (inc i) (unsigned-bit-shift-right v 8)))
        arr))))

(defn sub-byte-array
  "Extract subarray from byte array."
  ([^bytes arr start]
   (sub-byte-array arr start (- (alength arr) start)))
  ([^bytes arr start len]
   (let [result (byte-array len)]
     (System/arraycopy arr start result 0 len)
     result)))

(defn concat-byte-arrays
  "Concatenate multiple byte arrays."
  [& arrays]
  (let [total-len (reduce + (map alength arrays))
        result (byte-array total-len)]
    (loop [arrays arrays offset 0]
      (if (seq arrays)
        (let [^bytes arr (first arrays)]
          (System/arraycopy arr 0 result offset (alength arr))
          (recur (rest arrays) (+ offset (alength arr))))
        result))))

(defn bytes-starts-with?
  "Check if byte array starts with given prefix."
  [^bytes data ^bytes prefix]
  (let [data-len (alength data)
        prefix-len (alength prefix)]
    (and (>= data-len prefix-len)
         (loop [i 0]
           (if (< i prefix-len)
             (if (= (aget data i) (aget prefix i))
               (recur (inc i))
               false)
             true)))))

;; =============================================================================
;; AES CTR Cipher
;; =============================================================================

(defn create-aes-ctr-cipher
  "Create AES CTR cipher with given key and IV."
  [^bytes key ^bytes iv]
  (let [key-spec (SecretKeySpec. key "AES")
        iv-spec (IvParameterSpec. iv)
        cipher (Cipher/getInstance "AES/CTR/NoPadding")]
    (.init cipher Cipher/ENCRYPT_MODE key-spec iv-spec)
    cipher))

(defn cipher-update
  "Update cipher with data."
  [^bytes data ^javax.crypto.Cipher cipher]
  (.doFinal cipher data))

(defn cipher-update-inplace
  "Update cipher and return result without modifying the cipher state for next use."
  [^bytes data ^javax.crypto.Cipher cipher]
  (.update cipher data))

;; =============================================================================
;; SSL Context (trust all)
;; =============================================================================

(defn create-ssl-context
  "Create SSL context that trusts all certificates."
  []
  (let [trust-manager (reify X509TrustManager
                        (getAcceptedIssuers [_] (into-array X509Certificate []))
                        (checkClientTrusted [_ _ _])
                        (checkServerTrusted [_ _ _]))
        ssl-context (SSLContext/getInstance "TLS")]
    (.init ssl-context nil (into-array TrustManager [trust-manager]) nil)
    ssl-context))

(def ^:dynamic *ssl-context* (create-ssl-context))
(def ^:dynamic *ssl-socket-factory* (.getSocketFactory *ssl-context*))

;; =============================================================================
;; WebSocket Implementation
;; =============================================================================

(defn build-ws-frame
  "Build WebSocket frame with given opcode, data and optional masking."
  [opcode ^bytes data & {:keys [mask?] :or {mask? true}}]
  (let [length (alength data)
        fb (unchecked-byte (bit-or 0x80 opcode))
        baos (java.io.ByteArrayOutputStream.)]
    (.write baos fb)
    (cond
      (not mask?)
      (cond
        (< length 126)
        (.write baos length)
        (< length 65536)
        (do
          (.write baos 126)
          (.write baos (unsigned-bit-shift-right length 8))
          (.write baos (bit-and length 0xFF)))
        :else
        (do
          (.write baos 127)
          (doseq [i (range 7 -1 -1)]
            (.write baos (bit-and (unsigned-bit-shift-right length (* i 8)) 0xFF)))))
      :else
      (let [mask-key (secure-random-bytes 4)]
        (cond
          (< length 126)
          (do
            (.write baos (bit-or 0x80 length))
            (.write baos mask-key))
          (< length 65536)
          (do
            (.write baos (bit-or 0x80 126))
            (.write baos (unsigned-bit-shift-right length 8))
            (.write baos (bit-and length 0xFF))
            (.write baos mask-key))
          :else
          (do
            (.write baos (bit-or 0x80 127))
            (doseq [i (range 7 -1 -1)]
              (.write baos (bit-and (unsigned-bit-shift-right length (* i 8)) 0xFF)))
            (.write baos mask-key)))
        (.write baos (xor-mask data mask-key))))
    (.toByteArray baos)))

(defn read-ws-frame
  "Read WebSocket frame from input stream. Returns [opcode payload]."
  [^InputStream input]
  (let [hdr (byte-array 2)]
    ;; Read header
    (loop [read 0]
      (if (< read 2)
        (let [n (.read input hdr read (- 2 read))]
          (if (= n -1)
            (throw (java.io.EOFException. "Connection closed"))
            (recur (+ read n))))
        nil))
    (let [opcode (bit-and (aget hdr 0) 0x0F)
          length (bit-and (aget hdr 1) 0x7F)
          length (cond
                   (= length 126)
                   (let [buf (byte-array 2)]
                     (loop [read 0]
                       (when (< read 2)
                         (let [n (.read input buf read (- 2 read))]
                           (if (= n -1)
                             (throw (java.io.EOFException. "Connection closed"))
                             (recur (+ read n))))))
                     (bytes->int-be buf))
                   (= length 127)
                   (let [buf (byte-array 8)]
                     (loop [read 0]
                       (when (< read 8)
                         (let [n (.read input buf read (- 8 read))]
                           (if (= n -1)
                             (throw (java.io.EOFException. "Connection closed"))
                             (recur (+ read n))))))
                     (bytes->int-be buf))
                   :else length)
          masked? (pos? (bit-and (aget hdr 1) 0x80))
          mask-key (when masked?
                     (let [mask (byte-array 4)]
                       (loop [read 0]
                         (when (< read 4)
                           (let [n (.read input mask read (- 4 read))]
                             (if (= n -1)
                               (throw (java.io.EOFException. "Connection closed"))
                               (recur (+ read n))))))
                       mask))
          payload (byte-array length)]
      (loop [read 0]
        (when (< read length)
          (let [n (.read input payload read (- length read))]
            (if (= n -1)
              (throw (java.io.EOFException. "Connection closed"))
              (recur (+ read n))))))
      (let [final-payload (if masked?
                            (xor-mask payload mask-key)
                            payload)]
        [opcode final-payload]))))

(defn ws-send
  "Send data through WebSocket."
  [^OutputStream output ^bytes data]
  (let [frame (build-ws-frame ws-op-binary data :mask? true)]
    (.write output frame)
    (.flush output)))

(defn ws-recv
  "Receive data from WebSocket. Handles ping/pong/close frames internally."
  [^InputStream input ^OutputStream output]
  (loop []
    (let [[opcode payload] (read-ws-frame input)]
      (cond
        (= opcode ws-op-close)
        (do
          ;; Send close response
          (let [close-frame (build-ws-frame ws-op-close
                                           (if (> (alength payload) 1)
                                             (sub-byte-array payload 0 2)
                                             (byte-array 0))
                                           :mask? true)]
            (.write output close-frame)
            (.flush output))
          nil)
        (= opcode ws-op-ping)
        (do
          ;; Send pong
          (let [pong-frame (build-ws-frame ws-op-pong payload :mask? true)]
            (.write output pong-frame)
            (.flush output))
          (recur))
        (= opcode ws-op-pong)
        (recur)
        (or (= opcode 0x1) (= opcode 0x2))
        payload
        :else
        (recur)))))

(defn ws-connect
  "Connect to WebSocket endpoint. Returns {:input is :output os} map."
  [connect-host & {:keys [host-header path timeout-ms]
                   :or {path "/apiws" timeout-ms 10000}}]
  (try
    (let [host-header (or host-header connect-host)
          socket (.createSocket *ssl-socket-factory* connect-host 443)
          input (.getInputStream socket)
          output (.getOutputStream socket)
          ws-key (base64-encode (secure-random-bytes 16))
          request (str "GET " path " HTTP/1.1\r\n"
                       "Host: " host-header "\r\n"
                       "Upgrade: websocket\r\n"
                       "Connection: Upgrade\r\n"
                       "Sec-WebSocket-Key: " ws-key "\r\n"
                       "Sec-WebSocket-Version: 13\r\n"
                       "Sec-WebSocket-Protocol: binary\r\n"
                       "Origin: https://web.telegram.org\r\n"
                       "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                       "AppleWebKit/537.36 (KHTML, like Gecko) "
                       "Chrome/131.0.0.0 Safari/537.36\r\n"
                       "\r\n")]
      ;; Enable TCP_NODELAY
      (.setTcpNoDelay socket true)
      (.setSoTimeout socket timeout-ms)
      
      ;; Send request
      (.write output (.getBytes request StandardCharsets/UTF_8))
      (.flush output)
      
      ;; Read response
      (let [reader (java.io.BufferedReader. (java.io.InputStreamReader. input StandardCharsets/UTF_8))
            response-lines (loop [lines []]
                             (if-let [line (.readLine reader)]
                               (if (= line "")
                                 lines
                                 (recur (conj lines line)))
                               lines))
            first-line (first response-lines)
            parts (str/split first-line #" " 3)
            status-code (if (>= (count parts) 2)
                          (try
                            (Integer/parseInt (second parts))
                            (catch Exception _ 0))
                          0)]
        (if (= status-code 101)
          {:input input :output output :socket socket}
          (do
            (.close socket)
            (throw (ex-info "WebSocket handshake failed"
                           {:status-code status-code
                            :status-line first-line}))))))
      (catch Exception e
      (throw (ex-info "WebSocket connection failed"
                      {:connect-host connect-host
                       :host-header host-header
                       :path path
                       :reason (.getMessage e)}
                      e)))))

(defn ws-close
  "Close WebSocket connection."
  [{:keys [^java.net.Socket socket ^java.io.OutputStream output]}]
  (try
    (when output
      (let [close-frame (build-ws-frame ws-op-close (byte-array 0) :mask? true)]
        (.write output close-frame)
        (.flush output)))
    (catch Exception _))
  (when socket
    (try
      (.close socket)
      (catch Exception _))))

;; =============================================================================
;; MTProto Handshake
;; =============================================================================

(defn try-handshake
  "Try to parse MTProto handshake. Returns {:dc-id :is-media :proto-tag :dec-prekey-iv} or nil."
  [^bytes handshake ^bytes secret]
  (let [dec-prekey-and-iv (sub-byte-array handshake skip-len (+ prekey-len iv-len))
        dec-prekey (sub-byte-array dec-prekey-and-iv 0 prekey-len)
        dec-iv (sub-byte-array dec-prekey-and-iv prekey-len)
        dec-key (sha256 (concat-byte-arrays dec-prekey secret))
        cipher (create-aes-ctr-cipher dec-key dec-iv)
        decrypted (cipher-update handshake cipher)
        proto-tag (sub-byte-array decrypted proto-tag-pos 4)] 
      (when (or (bytes= proto-tag proto-tag-abridged)
                (bytes= proto-tag proto-tag-intermediate)
                (bytes= proto-tag proto-tag-secure))
        (let [dc-idx (bytes->int-le (sub-byte-array decrypted dc-idx-pos 2))
              dc-id (Math/abs dc-idx)
              is-media (neg? dc-idx)]
          {:dc-id dc-id
           :is-media is-media
           :proto-tag proto-tag
           :dec-prekey-iv dec-prekey-and-iv}))))

(defn generate-relay-init
  "Generate relay initialization handshake."
  [proto-tag dc-idx]
  (loop []
    (let [rnd (secure-random-bytes handshake-len)]
      (if (or (contains? reserved-first-byts (bit-and (aget rnd 0) 0xFF))
              (some #(bytes-starts-with? rnd %) reserved-starts)
              (bytes= (sub-byte-array rnd 4 4) reserved-continue))
        (recur)
        (let [enc-key (sub-byte-array rnd skip-len prekey-len)
              enc-iv (sub-byte-array rnd (+ skip-len prekey-len) iv-len)
              cipher (create-aes-ctr-cipher enc-key enc-iv)
              dc-bytes (int->bytes-le dc-idx 2)
              tail-plain (concat-byte-arrays proto-tag dc-bytes (secure-random-bytes 2))
              encrypted-full (cipher-update rnd cipher)
              keystream-tail (byte-array 8)]
          ;; XOR to get keystream
          (dotimes [i 8]
            (aset keystream-tail i (unchecked-byte (bit-xor (aget encrypted-full (+ 56 i)) (aget rnd (+ 56 i))))))
          ;; Encrypt tail
          (let [encrypted-tail (byte-array 8)]
            (dotimes [i 8]
              (aset encrypted-tail i (unchecked-byte (bit-xor (aget tail-plain i) (aget keystream-tail i)))))
            ;; Build result
            (let [result (byte-array handshake-len)]
              (System/arraycopy rnd 0 result 0 56)
              (System/arraycopy encrypted-tail 0 result 56 8)
              result)))))))

;; =============================================================================
;; WebSocket Domains
;; =============================================================================

(defn ws-domains
  "Get WebSocket domains for given DC."
  [dc is-media]
  (let [dc (if (= dc 203) 2 dc)]
    (if (or (nil? is-media) is-media)
      [(str "kws" dc "-1.web.telegram.org") (str "kws" dc ".web.telegram.org")]
      [(str "kws" dc ".web.telegram.org") (str "kws" dc "-1.web.telegram.org")])))

;; =============================================================================
;; Message Splitter
;; =============================================================================

(defprotocol IMessageSplitter
  (split-msg [this chunk])
  (flush-msg [this]))

(defn create-splitter
  "Create message splitter for given relay-init and proto."
  [^bytes relay-init proto-int]
  (letfn [(next-packet-len [^bytes plain-buf proto]
            (when (pos? (alength plain-buf))
              (cond
                (= proto proto-abridged-int)
                (let [first-byte (bit-and (aget plain-buf 0) 0xFF)]
                  (if (or (= first-byte 0x7F) (= first-byte 0xFF))
                    (when (>= (alength plain-buf) 4)
                      (let [payload-len (* (bytes->int-le (sub-byte-array plain-buf 1 3)) 4)
                            packet-len (+ 4 payload-len)]
                        (when (and (pos? payload-len) (>= (alength plain-buf) packet-len))
                          packet-len)))
                    (let [payload-len (* (bit-and first-byte 0x7F) 4)
                          packet-len (+ 1 payload-len)]
                      (when (and (pos? payload-len) (>= (alength plain-buf) packet-len))
                        packet-len))))

                (or (= proto proto-intermediate-int)
                    (= proto proto-padded-intermediate-int))
                (when (>= (alength plain-buf) 4)
                  (let [payload-len (bit-and (bytes->int-le (sub-byte-array plain-buf 0 4)) 0x7FFFFFFF)
                        packet-len (+ 4 payload-len)]
                    (when (and (pos? payload-len) (>= (alength plain-buf) packet-len))
                      packet-len)))

                :else nil)))]
    (let [key (sub-byte-array relay-init 8 32)
          iv (sub-byte-array relay-init 40 16)
          cipher (create-aes-ctr-cipher key iv)
          _ (cipher-update zero-64 cipher)
          state (atom {:proto proto-int
                       :cipher cipher
                       :cipher-buf (byte-array 0)
                       :plain-buf (byte-array 0)
                       :disabled false})]
    (reify IMessageSplitter
      (split-msg [_ chunk]
        (if (or (= 0 (alength chunk)) (:disabled @state))
          (if (= 0 (alength chunk))
            []
            [chunk])
          (do
            ;; Update buffers
            (swap! state update :cipher-buf concat-byte-arrays chunk)
            (swap! state update :plain-buf concat-byte-arrays (cipher-update chunk (:cipher @state)))
            ;; Extract packets
            (loop [parts []]
              (if-let [packet-len (next-packet-len (:plain-buf @state) (:proto @state))]
                (let [part (sub-byte-array (:cipher-buf @state) 0 packet-len)]
                  (swap! state update :cipher-buf sub-byte-array packet-len)
                  (swap! state update :plain-buf sub-byte-array packet-len)
                  (recur (conj parts part)))
                parts)))))
      (flush-msg [_]
        (let [buf (:cipher-buf @state)]
          (swap! state assoc :cipher-buf (byte-array 0) :plain-buf (byte-array 0))
          (if (= 0 (alength buf))
            []
            [buf])))))))

;; =============================================================================
;; Main Proxy Functions
;; =============================================================================

(defn create-ws-connection
  "Create WebSocket connection to Telegram. Returns connection map."
  [dc-id is-media & {:keys [dc-redirects timeout-ms]}]
  (let [target (or (get dc-redirects dc-id)
                   (get dc-default-ips dc-id))
        domains (ws-domains dc-id is-media)]
    (when-not target
      (throw (ex-info "No target IP for DC" {:dc-id dc-id})))
    (loop [domains domains]
      (if (empty? domains)
        (throw (ex-info "Failed to connect to any domain" {:dc-id dc-id}))
        (let [domain (first domains)]
          (if-let [ws (try
                        ;; Connect by hostname to preserve correct TLS/SNI behavior.
                        (ws-connect domain :timeout-ms (or timeout-ms 10000))
                        (catch Exception e
                          ;; Some environments have no IPv6 route for kws*.web.telegram.org.
                          ;; Fallback to known IPv4 DC target while keeping Host header.
                          (try
                            (ws-connect target :host-header domain :timeout-ms (or timeout-ms 10000))
                            (catch Exception e2
                              (log/warnf "Failed to connect to %s: %s | fallback-ip=%s reason=%s"
                                         domain
                                         (.getMessage e)
                                         target
                                         (or (:reason (ex-data e2)) (.getMessage e2)))
                              nil)))
                        (catch Exception e
                          (log/warnf "Failed to connect to %s: %s" domain (or (:reason (ex-data e)) (.getMessage e)))
                          nil))]
            {:ws ws
             :dc-id dc-id
             :is-media is-media
             :domain domain}
            (recur (rest domains))))))))

(defn send-message
  "Send message through WebSocket connection."
  [{:keys [ws]} ^bytes message]
  (ws-send (:output ws) message))

(defn recv-message
  "Receive message from WebSocket connection."
  [{:keys [ws]}]
  (ws-recv (:input ws) (:output ws)))

(defn close-connection
  "Close WebSocket connection."
  [{:keys [ws]}]
  (ws-close ws))

;; =============================================================================
;; Bridge: TCP Client <-> WebSocket
;; =============================================================================

(defn bridge-tcp-ws
  "Bridge between TCP client socket and WebSocket connection."
  [client-rw ws-conn]
  (let [{:keys [^java.io.InputStream input ^java.io.OutputStream output]} client-rw
        ws-in (get-in ws-conn [:ws :input])
        ws-out (get-in ws-conn [:ws :output])
        running? (atom true)]
    (future
      (try
        (loop []
          (when @running?
            (let [buf (byte-array 65536)
                  n (.read input buf)]
              (if (pos? n)
                (do (ws-send ws-out (sub-byte-array buf 0 n))
                    (recur))
                (reset! running? false)))))
        (catch Exception _ (reset! running? false))))
    (future
      (try
        (loop []
          (when @running?
            (if-let [data (ws-recv ws-in ws-out)]
              (do (.write output data)
                  (.flush output)
                  (recur))
              (reset! running? false))))
        (catch Exception _ (reset! running? false))))
    (while @running?
      (Thread/sleep 50))
    (close-connection ws-conn)))

(defn run-proxy
  "Run MTProto WebSocket proxy server."
  [& {:keys [port host secret dc-redirects]
      :or {port 1443
           host "127.0.0.1"
           secret (bytes->hex (secure-random-bytes 16))
           dc-redirects {2 "149.154.167.220" 4 "149.154.167.220"}}}]
  (let [server-socket (java.net.ServerSocket. port)
        _host host
        secret-bytes (hex->bytes secret)]
    (future
      (while (not (.isClosed server-socket))
        (let [client-socket (.accept server-socket)]
          (future
            (with-open [client client-socket
                        client-in (.getInputStream client)
                        client-out (.getOutputStream client)]
              (let [handshake (byte-array handshake-len)
                    n (loop [read 0]
                        (if (< read handshake-len)
                          (let [k (.read client-in handshake read (- handshake-len read))]
                            (if (= k -1) -1 (recur (+ read k))))
                          read))]
                (when (= n handshake-len)
                  (when-let [{:keys [dc-id is-media proto-tag]} (try-handshake handshake secret-bytes)]
                    (let [dc-idx (if is-media (- dc-id) dc-id)
                          relay-init (generate-relay-init proto-tag dc-idx)
                          ws-conn (create-ws-connection dc-id is-media :dc-redirects dc-redirects)]
                      (ws-send (get-in ws-conn [:ws :output]) relay-init)
                      (bridge-tcp-ws {:input client-in :output client-out} ws-conn))))))))))
    server-socket))

(defn connect-ws
  "Simple WebSocket connection for sending messages."
  [dc-id & {:keys [is-media dc-redirects]
            :or {is-media false
                 dc-redirects {2 "149.154.167.220"}}}]
  (create-ws-connection dc-id is-media :dc-redirects dc-redirects))

(defn send-msg [conn data]
  (send-message conn (if (bytes? data) data (.getBytes ^String data StandardCharsets/UTF_8))))

(defn recv-msg [conn]
  (recv-message conn))

(defn disconnect [conn]
  (close-connection conn))
