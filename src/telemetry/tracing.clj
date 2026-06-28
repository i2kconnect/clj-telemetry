(ns telemetry.tracing
  (:require [clojure.walk :as walk])
  (:import [io.opentelemetry.context Context]
           [io.opentelemetry.context.propagation ContextPropagators]
           [io.opentelemetry.sdk.trace SdkTracerProvider]
           [io.opentelemetry.sdk OpenTelemetrySdk]
           [io.opentelemetry.sdk.trace.export BatchSpanProcessor SimpleSpanProcessor]
           [io.opentelemetry.api OpenTelemetry]
           [io.opentelemetry.api.trace Span]
           [io.opentelemetry.api.trace.propagation W3CTraceContextPropagator]
           [io.opentelemetry.api.common Attributes]
           [io.opentelemetry.exporter.otlp.trace OtlpGrpcSpanExporter OtlpGrpcSpanExporterBuilder]
           [io.opentelemetry.exporter.zipkin ZipkinSpanExporter ZipkinSpanExporterBuilder]
           [java.util Base64]
           ))

(defn ^OtlpGrpcSpanExporter build-exporter-otlp
  [{:keys [endpoint timeout-ms headers basic-auth]
    :or {timeout-ms 30000}}]
  {:pre [endpoint]}
  (let [^OtlpGrpcSpanExporterBuilder builder (OtlpGrpcSpanExporter/builder)]
    (doto builder
      (.setEndpoint endpoint)
      (cond-> timeout-ms (.setTimeout timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)))
    (when (map? headers)
      (doseq [[key val] headers] (.addHeader builder (name key) (str val))))
    (when (and (map? basic-auth) (:username basic-auth) (:password basic-auth))
      (.addHeader builder "Authorization" (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes (str  (:username basic-auth) ":" (:password basic-auth)))))))
    (.build builder)))

(defn ^ZipkinSpanExporter build-exporter-zipkin
  [{:keys [endpoint timeout-ms]
    :or {timeout-ms 30000}}]
  {:pre [endpoint]}
  (let [^ZipkinSpanExporterBuilder builder (ZipkinSpanExporter/builder)]
    (doto builder
      (.setEndpoint endpoint)
      (cond-> timeout-ms (.setReadTimeout timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)))
    (.build builder)))

(defn ^OtlpGrpcSpanExporter build-exporter-jaeger
  "Builds an OTLP gRPC span exporter aimed at Jaeger's native OTLP gRPC
  endpoint (default port 4317). The Jaeger exporter was removed from
  OpenTelemetry Java after 1.34.1; Jaeger now ingests OTLP natively. The name
  and argument shape are preserved for callers that select this exporter by
  name. If :endpoint is given it is used verbatim; otherwise :ip/:port (default
  4317) compose an http endpoint, falling back to http://localhost:4317."
  [{:keys [endpoint ip port timeout-ms] :or {timeout-ms 30000 port 4317}}]
  (let [port (cond (string? port) (Integer/parseInt port)
                   (integer? port) port
                   :else (throw (Exception. "Jaeger exporter port must be integer or string")))
        endpoint (or endpoint
                     (when ip (str "http://" ip ":" port))
                     "http://localhost:4317")
        ^OtlpGrpcSpanExporterBuilder builder (OtlpGrpcSpanExporter/builder)]
    (doto builder
      (.setEndpoint endpoint)
      (cond-> timeout-ms (.setTimeout timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)))
    (.build builder)))

(defn build-batch-span-processor
  [exporter]
  (-> (BatchSpanProcessor/builder exporter)
      .build))

(defn build-simple-span-processor
  [exporter]
  (SimpleSpanProcessor/create exporter))

(defn init-open-telemetry
  [span-processor]
  {:pre [span-processor]
   :post [%]}
  (-> (OpenTelemetrySdk/builder)
      (.setTracerProvider (-> (SdkTracerProvider/builder)
                              (.addSpanProcessor span-processor)
                              (.build)))
      (.build)))

(defn shutdown-open-telemetry
  [open-telemetry]
  (-> (.getSdkTracerProvider open-telemetry)
      (.shutdown)))

(defn get-tracer
  [open-telemetry & [library-name]]
  {:pre [open-telemetry]
   :post [%]}
  (if open-telemetry
    (if-let [tracer (.getTracer open-telemetry (or library-name "telemetry.tracing"))]
      tracer
      (.build (.tracerBuilder open-telemetry (or library-name "telemetry.tracing"))))))

(defn create-span
  ([tracer id]
   (create-span tracer id nil))
  ([tracer id parent]
   {:pre [(or (nil? parent) (instance? Span parent))]}
   (if tracer
     (let [span (if parent
                  (-> (.spanBuilder tracer id)
                      (.setParent (-> (Context/current)
                                      (.with parent))))
                  (-> (.spanBuilder tracer id)
                      (.setNoParent)))]
       (.startSpan span)))))

(defn end-span
  [span]
  (if span
    (.end span)))

(defn span-attributes
  [span attributes]
  (if span
    (doseq [[k v] attributes]
      (.setAttribute span (str k) (str v)))))

(defn attrs [m]
  (let [builder (Attributes/builder)]
    (doseq [[k v] m]
      (.put builder (str k) (str v)))
    (.build builder)))

(defn add-event
  ([span message]
   (add-event span message nil))
  ([span message m]
   (if span
     (if (and m
              (not (empty? m)))
       (.addEvent span message (attrs m))
       (.addEvent span message)))))

(comment
  (def exporter (build-exporter-jaeger {:ip "localhost" :port 4317}))
  (def span-processor (build-simple-span-processor exporter))
  (def open-telemetry (init-open-telemetry exporter))
  (def tracer (get-tracer open-telemetry "test.tracing"))
  )



