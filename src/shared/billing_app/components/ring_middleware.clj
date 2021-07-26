(ns billing-app.components.ring-middleware
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [cognitect.transit]
    [com.fulcrologic.fulcro.server.api-middleware :as server]
    [com.fulcrologic.fulcro.networking.file-upload :as file-upload]
    [com.fulcrologic.rad.blob :as blob]
    [mount.core :refer [defstate]]
    [hiccup.page :refer [html5]]
    [ring.middleware.defaults :refer [wrap-defaults]]
    [ring.middleware.gzip :refer [wrap-gzip]]
    [billing-app.components.blob-store :as bs]
    [billing-app.components.config :as config]
    [billing-app.components.parser :as parser]
    [taoensso.timbre :as log]
    [ring.util.response :as resp])
  (:import (java.util Base64)))

(defn index [csrf-token]
  (html5
    [:html {:lang "en"}
     [:head {:lang "en"}
      [:title "Billing App"]
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"}]

      [:link {:href "https://cdn.jsdelivr.net/npm/fomantic-ui@2.7.8/dist/semantic.min.css"
              :rel  "stylesheet"}]
      [:link {:rel "shortcut icon" :href "data:image/x-icon;," :type "image/x-icon"}]
      [:script (str "var fulcro_network_csrf_token = '" csrf-token "';")]]
     [:body
      [:div#app]
      [:script {:src "/js/main/main.js"}]]]))

(defn wrap-api [handler uri]
  (fn [request]
    (if (= uri (:uri request))
      (server/handle-api-request (:transit-params request)
        (fn [query]
          (parser/parser {:ring/request request}
            query)))
      (handler request))))

(def not-found-handler
  (fn [req]
    {:status 404
     :body   {}}))

(defn wrap-html-routes [ring-handler]
  (fn [{:keys [uri anti-forgery-token] :as req}]
    (if (or (str/starts-with? uri "/api")
          (str/starts-with? uri "/images")
          (str/starts-with? uri "/files")
          (str/starts-with? uri "/js"))
      (ring-handler req)

      (-> (resp/response (index anti-forgery-token))
        (resp/content-type "text/html")))))

(defn jwt-claims
  "Simple, DYI parsing of a JWT token *without any verification*"
  [^String jwt-token]
  ;; NOTE: Verifying the token omitted for simplicity but in prod code it should be done; see e.g.
  ;; https://github.com/funcool/buddy-sign/blob/558653da082d42377188c5c2660d0454e422fe33/src/buddy/sign/jws.clj#L153
  (let [decode #(.decode (Base64/getDecoder) ^String %)
        ^bytes jwt-bytes
        (-> jwt-token
            (clojure.string/split #"\.")
            second
            decode)]
    (-> jwt-bytes
        (String.)
        (json/read-str))))

(defn employee-authorized?
  "Is the user, logged in with the company OIDC provider, authorized to access the app?
  The user's OIDC ID Token is expected to be present in the Authorization header as the bearer."
  [auth-header]
  {:pre [auth-header (string? auth-header)]}
  (let [{:strs [iss roles]}
        (jwt-claims (subs auth-header (count "Bearer ")))]
    (when (= iss "https://login.microsoftonline.com/cd5e4040-44d9-4a11-93dd-e348c3d4fafc/v2.0") ; issuer = Azure AD
      (some #{"developer" "admin"} roles))))

(defn authorize-user [userid req] ;(def *h (:headers req))
  (let [authorized? (employee-authorized? (-> req :headers (get "authorization")))]
    (cond
      (nil? userid)
      "Missing user info, not accessed via the auth proxy?!"

      authorized?
      ::authorized

      :else
      (do
        (log/info "User" userid "is not authorized to access the app.")
        (str "User '" userid "' is not authorized to access the app due to missing role(s).")))))

(defn wrap-authorization
  "Verify the user is logged in and authorized"
  [ring-handler]
  (fn [{:keys [uri headers session] :as req}]
    (let [was-authorized? (-> session :authorized?)
          protected-url?  (or (str/starts-with? uri "/api")
                              (str/starts-with? uri "/files"))
          resp403         {:status  403
                           :session (assoc session :authorized? false)
                           :headers {"x-server" "Billing App", "content-type" "text/plain"}}
          userid          (get headers "x-forwarded-userid")
          res             (delay (authorize-user userid req))]
      (cond
        ;; Go to /fake-auth in the browser to get an authenticated session in dev (if :dev/bypass-authorization? false)
        (and (= uri "/fake-auth") (:dev/allow-fake-authorization? config/config))
        (do
          (log/info "New FAKE OK login")
          (assoc-in (resp/redirect "/") [:session :authorized?] true))

        (or was-authorized? (not protected-url?) (:dev/bypass-authorization? config/config))
        (ring-handler req)

        (false? was-authorized?) ;; checked and denied previously
        (assoc resp403 :body (:reason session))

        (nil? was-authorized?) ;; first-time user
        (if (= @res ::authorized)
          (do
            (log/info "New OK login by" userid)
            (assoc-in (ring-handler req) [:session :authorized?] true))
          (do
            (log/info "Access denied for" userid uri)
            (-> resp403
                (assoc-in [:session :reason] @res)
                (assoc :body @res))))))))

(def ^:static version (System/getenv "GIT_SHA"))

(defn wrap-healthz
  "Return 200 for /healthz, used by the Load Balancer health check"
  [handler]
  (fn [{:keys [uri] :as req}]
    (if (= uri "/healthz")
      {:status 200
       :body (str "Ver " version)
       :headers {"x-server" "Billing App", "content-type" "text/plain"}}
      (handler req))))

(defn wrap-security [handler]
  (fn [req]
    (update-in (handler req) [:headers]
               ;; Don't reveal to the hackers the server running underneath
               assoc "server" "Billing App")))

(def transit-write-handlers
  {java.lang.Throwable
   (cognitect.transit/write-handler
     "s"
     (fn [^java.lang.Throwable t]
       (str "EXCEPTION: " (.getMessage t))))

   java.time.Instant
   (cognitect.transit/write-handler
     "t"
     (fn [^java.time.Instant inst]
       (.format
         (com.cognitect.transit.impl.AbstractParser/getDateTimeFormat)
         (java.util.Date/from inst))))})

(defstate middleware
  :start
  (let [defaults-config (:ring.middleware/defaults-config config/config)]
    (-> not-found-handler
      (wrap-api "/api")
      (file-upload/wrap-mutation-file-uploads {})
      (blob/wrap-blob-service "/images" bs/image-blob-store)
      (blob/wrap-blob-service "/files" bs/file-blob-store)
      (server/wrap-transit-params {})
      (server/wrap-transit-response {:opts {:handlers transit-write-handlers}})
      (wrap-html-routes)
      (wrap-authorization)
      (wrap-healthz)
      (wrap-defaults defaults-config)
      (wrap-security)
      (wrap-gzip))))

