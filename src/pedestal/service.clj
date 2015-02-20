(ns pedestal.service
  (:require [io.pedestal.http :as bootstrap]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.ring-middlewares :as ring]
            [io.pedestal.log :as log]
            [ring.swagger.schema]
            [pedestal.swagger.core :as swagger]
            [pedestal.swagger.doc :as doc]
            [schema.core :as s]
            [ring.util.response :as ring-resp]
            [clojure.core.async :refer [<! go timeout]]))

(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

(swagger/defhandler list-books
  {:description "list"
   :summary "'()"
   :parameters {:query {:how-many? (ring.swagger.schema/describe s/Int "slkjdflkdj")}}
   :responses {200 {:description "stub"
                    :schema s/Str}}}
  [request]
  (ring-resp/response "books!"))

(defn async-handler
  [handler]
  (let [handler-fn (if (fn? handler) handler (comp :response (:enter handler)))]
    (with-meta
      (interceptor/interceptor
       :name (str "async-" (or (::name (meta handler)) (:name handler)))
       :enter (fn [context]
                (go (let [response (<! (handler-fn (:request context)))]
                      (log/info :response response)
                      (assoc context :response response)))))
      (meta handler))))

(defmacro defasynchandler
  [name doc args & body]
  `(def ~name (with-meta (async-handler (with-meta (fn ~args ~@body)
                                          {::name ~name}))
                {:pedestal.swagger.doc/doc ~doc})))

(def Url
  s/Str)

(s/defschema Book
  "Description?"
  {:name s/Str
   :cover-image Url
   :description s/Str})

(defasynchandler describe-book
  {:summary "Describe the book!"
   :description "It describes the book!"
   :parameters {:path {:id s/Int}}
   :responses {200 {:description "A book!"
                    :schema Book}}}
  [request]
  (go
    (log/info :req (pr-str request))
    (let [id (get-in request [:path-params :id])]
      (ring-resp/response
       (condp = id
         1 {:name "A Story of some things!"
            :description "It's a book! It's got pages!"}
         2 {:name "Another one."
            :cover-image "bar"
            :description "It's another book! It's also got pages!"})))))

(swagger/defhandler who-cares
  {:description "also POST it!"
   :summary "POST it!"
   :parameters {:body {:id s/Str}}
   :responses {200 {:description "Done"
                    :schema s/Str}}}
  [_]
  (ring-resp/response "done"))

(swagger/defroutes routes
  {:title "My App"
   :description "It's my app!"
   :version "42 of course!"}
  [[["/" {:get home-page}
     ;; Set default interceptors for /about and any other paths under /
     ^:interceptors [(body-params/body-params)
                     bootstrap/json-body
                     (swagger/body-params)
                     (swagger/keywordize-params :form-params :headers)
                     (swagger/coerce-params)
                     (swagger/validate-response)]
     ["/about" {:get about-page}]
     ["/test" ^:interceptors [ring/multipart-params]
      {:post who-cares}]
     ["/book" {:get list-books}
      ["/:id"
       {:get describe-book}]]
     ["/doc" {:get [(swagger/swagger-doc)]}]]
    ["/ui/*resource" {:get [(swagger/swagger-ui)]}]]])

;; Consumed by pedestal.server/create-server
;; See bootstrap/default-interceptors for additional options you can configure
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; :bootstrap/interceptors []
              ::bootstrap/routes routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::bootstrap/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"

              ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
              ::bootstrap/type :jetty
              ;;::bootstrap/host "localhost"
              ::bootstrap/port 8080})
