(ns puppetlabs.services.request-handler.request-handler-core
  (:import (java.security.cert X509Certificate)
           (java.util HashMap)
           (java.io StringReader)
           (com.puppetlabs.puppetserver JRubyPuppetResponse))
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.certificate-authority.core :as ssl]
            [ring.middleware.params :as ring-params]
            [ring.middleware.nested-params :as ring-nested-params]
            [ring.util.codec :as ring-codec]
            [ring.util.response :as ring-response]
            [slingshot.slingshot :as sling]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(def header-client-cert-name
  "Name of the HTTP header through which a client certificate can be passed
  for a request"
  "x-client-cert")

(defn unmunge-http-header-name
  [setting]
  "Given the value of a Puppet setting which contains a munged HTTP header name,
  convert it to the actual header name in all lower-case."
  (->> (string/split setting #"_")
       rest
       (string/join "-")
       string/lower-case))

(defn config->request-handler-settings
  "Given an entire Puppet Server configuration map, return only those keys
  which are required by the request handler service."
  [{:keys [puppet-server master]}]
  {:allow-header-cert-info   (true? (:allow-header-cert-info master))
   :ssl-client-verify-header (unmunge-http-header-name
                               (:ssl-client-verify-header puppet-server))
   :ssl-client-header        (unmunge-http-header-name
                               (:ssl-client-header puppet-server))})

(defn get-cert-common-name
  "Given a request, return the Common Name from the client certificate subject."
  [ssl-client-cert]
  (if-let [cert ssl-client-cert]
    (if-let [cert-dn (-> cert .getSubjectX500Principal .getName)]
      (if-let [cert-cn (ks/cn-for-dn cert-dn)]
        cert-cn
        (log/errorf "cn not found in client certificate dn: %s"
                   cert-dn))
      (log/error "dn not found for client certificate subject"))))

(defn response->map
  "Converts a JRubyPuppetResponse instance to a map."
  [response]
  { :pre [(instance? JRubyPuppetResponse response)]
    :post [(map? %)] }
    { :status  (.getStatus response)
      :body    (.getBody response)
      :headers {"Content-Type"     (.getContentType response)
                "X-Puppet-Version" (.getPuppetVersion response)}})

(defn wrap-params-for-jruby
  "Pull parameters from the URL query string and/or urlencoded form POST
   body into the ring request map.  Includes some special processing for
   a request destined for JRubyPuppet."
  [request]
  ; Need to slurp the request body before invoking any of the ring
  ; middleware functions because a handle to the body payload needs to be passed
  ; through to JRubyPuppet and the body won't be around to slurp if any of
  ; the ring functions happen to slurp it up first.  This would happen for a
  ; 'application/x-www-form-urlencoded' form post where ring needs to slurp
  ; in the body of the request in order to parse out parameters from the form.

  ; Arguably could use "ISO-8859-1" as the default encoding if one is not
  ; specified on the request, but "UTF-8" is what ring would use as well.
  (let [body-string (slurp (:body request)
                           :encoding (or (:character-encoding request)
                                         "UTF-8"))]
        (->
          request
          ; Leave the slurped content under an alternate key so that it
          ; is available to be proxied on to the JRubyPuppet request.
          (assoc :body-string body-string)
          ; Body content has been slurped already so wrap it in a new reader
          ; so that a copy of it can be obtained by ring middleware functions,
          ; if needed.
          (assoc :body (StringReader. body-string))
          ; Compojure request may have destructured parameters from subportions
          ; of the URL into the params map by this point.  Clear this out
          ; before invoking the ring middleware param functions so that keys
          ; pulled from the query string or form body parameters don't
          ; inadvertently conflict.
          (assoc :params {})
          ; Defer to ring middleware to pull out parameters from the query
          ; string and/or form body.
          ring-params/params-request)))

(def unauthenticated-client-info
  "Return a map with default info for an unauthenticated client"
  {:client-cert-cn nil
   :authenticated  false})

(defn header-auth-info
  "Return a map with authentication info based on header content"
  [header-dn-name header-dn-val header-auth-val]
  (if (ssl/valid-x500-name? header-dn-val)
    {:client-cert-cn (ssl/x500-name->CN header-dn-val)
     :authenticated  (= "SUCCESS" header-auth-val)}
    (do
      (if-not (nil? header-dn-val)
        (log/errorf "The DN '%s' provided by the HTTP header '%s' is malformed."
                    header-dn-val header-dn-name))
      unauthenticated-client-info)))

(defn throw-bad-request!
  "Throw a ::bad-request type slingshot error with the supplied message"
  [message]
  (sling/throw+ {:type ::bad-request
                 :message message}))

(defn header-cert->pem
  "Convert the header cert value into a PEM string"
  [header-cert]
  (try
    (ring-codec/url-decode header-cert)
    (catch Exception e
      (throw-bad-request!
        (str "Unable to URL decode the "
             header-client-cert-name
             " header: "
             (.getMessage e))))))

(defn pem->certs
  "Convert a pem string into certificate objects"
  [pem]
  (with-open [reader (StringReader. pem)]
    (try
      (ssl/pem->certs reader)
      (catch Exception e
        (throw-bad-request!
          (str "Unable to parse "
               header-client-cert-name
               " into certificate: "
               (.getMessage e)))))))

(defn header-cert
  "Return an X509Certificate or nil from a string encoded for transmission
  in an HTTP header."
  [header-cert-val]
  (if header-cert-val
    (let [pem        (header-cert->pem header-cert-val)
          certs      (pem->certs pem)
          cert-count (count certs)]
      (condp = cert-count
        0 (throw-bad-request!
            (str "No certs found in PEM read from " header-client-cert-name))
        1 (first certs)
        (throw-bad-request!
          (str "Only 1 PEM should be supplied for "
               header-client-cert-name
               " but "
               cert-count
               " found"))))))

(defn auth-maybe-with-client-header-info
  "Return authentication info based on client headers"
  [config headers]
  (let [header-dn-name   (:ssl-client-header config)
        header-dn-val    (get headers header-dn-name)
        header-auth-name (:ssl-client-verify-header config)
        header-auth-val  (get headers header-auth-name)
        header-cert-val  (get headers header-client-cert-name)]
    (if (:allow-header-cert-info config)
      (-> (header-auth-info header-dn-name
                            header-dn-val
                            header-auth-val)
          (assoc :client-cert (header-cert header-cert-val)))
      (do
        (doseq [[header-name header-val] {header-dn-name           header-dn-val
                                          header-auth-name         header-auth-val
                                          header-client-cert-name  header-cert-val}]
          (if header-val
            (log/warn "The HTTP header" header-name "was specified,"
                      "but the master config option allow-header-cert-info"
                      "was either not set, or was set to false."
                      "This header will be ignored.")))
        unauthenticated-client-info))))

(defn auth-maybe-with-ssl-info
  "Merge information from the SSL client cert into the jruby request if
  available and information was not expected to be provided via client headers"
  [config ssl-client-cert request]
  (if (:allow-header-cert-info config)
    request
    (let [cn   (get-cert-common-name ssl-client-cert)]
      (merge request {:client-cert    ssl-client-cert
                      :client-cert-cn cn
                      :authenticated  (not (nil? cn))}))))

(defn as-jruby-request
  "Given a ring HTTP request, return a new map that contains all of the data
   needed by the ruby HTTP layer to process it.  This function does a couple
   things that are a bit weird:
      * It reads the entire request body into memory.  This is not ideal for
        performance and memory usage, but we have to ship this thing over to
        JRuby, so I don't think there's any way around this.
      * It also extracts the client DN and certificate and includes that
        in the map it returns, because it's needed by the ruby layer.  It is
        possible that the HTTPS termination has happened external to Puppet
        Server.  If so, then the DN, authentication status, and, optionally, the
        certificate will be provided by HTTP headers."
  [config request]
  (let [headers   (:headers request)
        jruby-req {:uri            (:uri request)
                   :params         (:params request)
                   :remote-addr    (:remote-addr request)
                   :headers        headers
                   :body           (:body-string request)
                   :request-method (-> (:request-method request)
                                       name
                                       string/upper-case)}]
    (merge jruby-req
           (->> (auth-maybe-with-client-header-info config
                                                    headers)
                (auth-maybe-with-ssl-info config
                                          (:ssl-client-cert request))))))

(defn make-request-mutable
  [request]
  "Make the request mutable.  This is required by the ruby layer."
  (HashMap. request))

(defn bad-request?
  [x]
  "Determine if the supplied slingshot message is for a 'bad request'"
  (when (map? x)
    (= (:type x)
       :puppetlabs.services.request-handler.request-handler-core/bad-request)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn handle-request
  [request jruby-instance config]
  (sling/try+
    (->> request
         wrap-params-for-jruby
         (as-jruby-request config)
         clojure.walk/stringify-keys
         make-request-mutable
         (.handleRequest jruby-instance)
         response->map)
    (catch bad-request? {:keys [message]}
      (log/errorf "Error 400 on SERVER at %s: %s" (:uri request) message)
      (-> (ring-response/response message)
          (ring-response/status 400)
          (ring-response/content-type "text/plain")))))