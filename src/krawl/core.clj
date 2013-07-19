(ns krawl.core
  (:require [clj-http.client :as client]
            [pl.danieljanus.tagsoup :as tagsoup :only [parse-string]]
            [clojure.core.match :refer [match]]
            [taoensso.timbre :as timbre :refer
             (trace debug info warn error fatal)]
            ;;[cemerick.url :refer [url url-encode]] <-- does not work
            )
  (:import (java.util.concurrent Executors LinkedBlockingQueue TimeUnit)
           java.net.URI
           java.net.URL
           java.util.ArrayList
           com.isaksky.krawl.UriHelper))

(timbre/set-config! [:appenders :spit :enabled?] true)

(timbre/set-config! [:shared-appender-config :spit-filename] "krawl.log")

;; (defn create-uri [s]
;;     ;; Needed because of some weird query encoding issues
;;     (try
;;       (str (url base-url-str url-str))
;;       (catch java.net.URISyntaxException ex
;;         (str (url base-url-str (url-encode url-str)))))
;;     (let [url (URL. s)]
;;       (URI. (.getProtocol url) (.getHost url) (.getPath url) (.getQuery url) nil)))

(defn interactive-content?
  "Check if an html element (in hiccup form) is interactive content. See http://www.w3.org/html/wg/drafts/html/master/dom.html#interactive-content-0"
  [node]
  (match node
         [(:or :a :button :details :embed :iframe :keygen :label :select :textarea :video) & _] true
         [(:or :audio :video) {:controls _} & _] true
         [(:or :img :object) {:usemap _} & _] true
         [:input attrs & _ ] (not (= "hidden" (:type attrs)))
         :else false))

(defn get-anchors [nodes]
  (let [ret (atom #{})]
    (clojure.walk/prewalk
     (fn [node] (do (match node
                          [:a & _] (swap! ret #(conj % node))
                          :else node)
                   ;; determine whether to keep searching this nodes children.
                   ;; we can skip interactive-content, since that is not allowed by html spec.
                   ;; see http://www.w3.org/html/wg/drafts/html/master/text-level-semantics.html#the-a-element
                   (when-not (interactive-content? node) node)))
     nodes)
    @ret))

(defn get-uris [anchors base-url-str]
  (->> anchors
       (map #(get-in % [1 :href]))
       (remove clojure.string/blank?)
       (map (fn [url-str]
              ;;(create-uri base-url-str url-str)
              (let [uri (URI. (UriHelper/encodeUrl url-str))
                    base-uri (URI. base-url-str)]
                (if (.isAbsolute uri)
                  (str uri)
                  (str (doto (.resolve base-uri uri)
                         .normalize))))))))

(defonce http-request-executor (Executors/newFixedThreadPool 50))

(def uri-getting-service (java.util.concurrent.ExecutorCompletionService. http-request-executor))

(def conn-mgr (clj-http.conn-mgr/make-reusable-conn-manager {:timeout 10
                                                             :threads 40
                                                             :default-per-route 2
                                                             :insecure? true}))
(def uris-last-fetched-at (atom {}))

(def uris-last-requested-at (atom {}))

(def last-response (atom nil))

(defn uri->response [uri]
  (info (format "Getting : %s" (.toString uri)))
  (swap! uris-last-requested-at assoc uri (java.lang.System/currentTimeMillis))
  (let [result (try (client/get (.toString uri) {:connection-manager conn-mgr})
                    (catch Exception ex
                      {:exception ex
                       :trace-redirects [(.toString uri)]}))]
    (reset! last-response result)
    (info (format "Got : %s" (.toString uri)))
    (when-not (:exception result)
      (swap! uris-last-fetched-at assoc uri (java.lang.System/currentTimeMillis)))
    result))

(defonce request-executor (Executors/newFixedThreadPool
                           (dec (.availableProcessors (Runtime/getRuntime)))))

(def uri-finding-service (java.util.concurrent.ExecutorCompletionService. request-executor))

(defn html-str>uris [html-str base-uri]
  (let [anchors (-> html-str
                    tagsoup/parse-string
                    get-anchors ;timbre/spy
                    )]
    (get-uris anchors base-uri)))

(def last-uris (atom nil))

(defn response->uris [response]
  #_(info "response->uris on " response)
  (if-let [ex (:exception response)]
    (error ex)
    (let [base-uri-str (last (:trace-redirects response))]
      (info (format "Processing response from %s" base-uri-str))
      (if (string? (:body response))
        (do (let [uris (html-str>uris (:body response) base-uri-str)]
              (reset! last-uris uris)
              uris))
        (error "Body was not a string, wtf.")))))

(def root-uri "http://example.iana.org/")

(.submit uri-getting-service #(uri->response root-uri))

;; Communicate between executors
(def comm-executor (Executors/newSingleThreadScheduledExecutor))

(defn schedule
  "Polls the two queues of completed items and schedules more jobs."
  []
  (try
    (do
      (loop [response-future (.poll uri-getting-service)]
        (when response-future
          (try (when-let [response (.get response-future)]
              (let [source-url-str (-> response :trace-redirects last)]
                (info (format "Found response from %s" source-url-str))
                (.submit uri-finding-service #(response->uris response))))
            (catch Exception ex
              (error (format "Exception with response:\n%s\nKeep going." ex))))
          (recur (.poll uri-getting-service))))
      (loop [uris-future (.poll uri-finding-service)]
        (when uris-future
          (try
            (when-let [uris (.get uris-future)]
              (timbre/spy uris)
              (doseq [uri uris]
                (when (and ;(= (.getHost uri) (.getHost root-uri))
                       (not (@uris-last-requested-at uri)))
                  (info (format "Enqueuing uri %s" uri))
                  (.submit uri-getting-service #(uri->response uri)))))
            (catch Exception ex
              (error (format "Exception with uris:\n%s" ex))))
          (recur (.poll uri-finding-service)))))
    (catch Exception ex
      (do
        (error (format "Uncaught comm exception!:\n%s\nShutting down." ex))
        (throw ex)))))

(def comm-job (atom nil))

(defn start []
  (try (when @comm-job
         (.cancel @comm-job)))
  (reset! comm-job (.scheduleWithFixedDelay comm-executor
                                            schedule
                                            0
                                            4000
                                            TimeUnit/MILLISECONDS)))

(defn stop []
  (when @comm-job
    (.cancel @comm-job true)))
