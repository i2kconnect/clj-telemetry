(ns telemetry.tracing-test
  (:require [clojure.test :refer :all]
            [telemetry.tracing :refer :all])
  (:import [io.opentelemetry.exporter.jaeger JaegerGrpcSpanExporter]
           [io.opentelemetry.exporter.otlp.trace OtlpGrpcSpanExporter]
           [io.opentelemetry.exporter.zipkin ZipkinSpanExporter]
           [io.opentelemetry.sdk.trace.export BatchSpanProcessor SimpleSpanProcessor]
           [io.opentelemetry.exporters.inmemory InMemorySpanExporter]
           [io.opentelemetry.api.trace Span]))

(defn build-in-memory-exporter
  []
  (InMemorySpanExporter/create))

(def ^:dynamic *span-processor* nil)
(def ^:dynamic *open-telemetry* nil)
(def ^:dynamic *tracer* nil)

(defmacro with-memory-test
  [exporter & body]
  `(binding [*span-processor* (build-simple-span-processor ~exporter)]
     (binding [*open-telemetry* (init-open-telemetry *span-processor*)]
       (binding[*tracer* (get-tracer *open-telemetry* "tracing.test")]
         (when (not *span-processor*)
           (throw (Exception. "No span processor")))
         (when (not *open-telemetry*)
           (throw (Exception. "No open telemetry")))
         (when (not *tracer*)
           (throw (Exception. "No tracer")))
        (try
           ~@body
          (finally (shutdown-open-telemetry *open-telemetry*)))))))

(deftest test-build-exporter-otlp
  (let [exporter (build-exporter-otlp {:endpoint "https://localhost/otlp"})]
    (is (instance? OtlpGrpcSpanExporter exporter))))

(deftest test-build-exporter-zipkin
  (let [exporter (build-exporter-zipkin {:endpoint "https://localhost/zipkin"})]
    (is (instance? ZipkinSpanExporter exporter))))

(deftest test-build-exporter-jaeger
  (let [exporter (build-exporter-jaeger {:endpoint "https://localhost:14250"})]
    (is (instance? JaegerGrpcSpanExporter exporter))))

(deftest test-build-batch-span-processor
  (let [processor (build-batch-span-processor (build-exporter-jaeger {:endpoint "https://localhost:14250"}))]
    (is (instance? BatchSpanProcessor processor))))

(deftest test-build-simple-span-processor
  (let [processor (build-simple-span-processor (build-exporter-jaeger {:endpoint "https://localhost:14250"}))]
    (is (instance? SimpleSpanProcessor processor))))

(deftest test-span
  []
  (let [exporter (build-in-memory-exporter)]
    (is (instance? InMemorySpanExporter exporter))
    (with-memory-test exporter
      (is *tracer*)
      (let [^Span span1 (create-span *tracer* "span-1")]
        (is span1)
        (add-event span1 "1. first event")
        (add-event span1 "1. second event")
        (let [^Span span2 (create-span *tracer* "span-2")]
          (is span2)
          (add-event span2 "span 2. first event")
          (add-event span2 "span 2. second event" {"attr1" "attr1 value"
                                                   "attr2" "attr2 value2"})
          (.end span2))
        (.end span1))
      (.forceFlush *span-processor*)
      (let [spans (.getFinishedSpanItems exporter)]
        (is (= 2 (count spans)))
        (let [span-1 (some #(and (= (.getName %) "span-1") %) spans)
              span-2 (some #(and (= (.getName %) "span-2") %) spans)]
          (is span-1)
          (is span-2)
          (is (= (.getParentSpanId span-1) "0000000000000000"))
          (is (= (.getParentSpanId span-2) "0000000000000000"))
          (is (= 2 (count (.getEvents span-1))))
          (is (= "1. first event") (.getName (first (.getEvents span-1))))
          (is (= 2 (count (.getEvents span-2))))
          (is (= "span 2. second event") (.getName (second (.getEvents span-2))))
          (is (= "attr1 value") (get (.asMap (.getAttributes (second (.getEvents span-2)))) "attr1")))))))

(deftest test-parent-span
  []
  (let [exporter (build-in-memory-exporter)]
    (with-memory-test exporter
      (let [root (create-span *tracer* "test-parent-span")]
        (let [child (create-span *tracer* "test-parent-span child" root)]
          (.end child)
        (.end root)))
      (.forceFlush *span-processor*)
      (let [spans (.getFinishedSpanItems exporter)]
        (is (= 2 (count spans)))
        (let [span-1 (some #(and (= (.getName %) "test-parent-span") %) spans)
              span-2 (some #(and (= (.getName %) "test-parent-span child") %) spans)]
          (is span-1)
          (is span-2)
          (is (= (.getParentSpanId span-1) "0000000000000000"))
          (is (= (.getParentSpanId span-2) (.getSpanId span-1))))))))

        
(deftest test-parallel-spans
  (let [exporter (build-in-memory-exporter)]
    (with-memory-test exporter
      (let [span-1 (create-span *tracer* "test-parallel-1")
            span-2 (create-span *tracer* "test-parallel-2")]
        (add-event span-1 "begin")
        (add-event span-2 "event: a")
        (Thread/sleep 100)
        (add-event span-1 "event: b")
        (Thread/sleep 100)
        (add-event span-2 "event: c")
        (Thread/sleep 100)
        (add-event span-1 "event: d")
        (Thread/sleep 100)
        (add-event span-2 "event: e")
        (Thread/sleep 100)
        (add-event span-1 "event: f")
        (add-event span-2 "span: after end span")
        (.end span-1)
        (.end span-2))
      (.forceFlush *span-processor*)
      (let [spans (.getFinishedSpanItems exporter)]
        (is (= 2 (count spans)))
        (let [span-1 (some #(and (= (.getName %) "test-parallel-1") %) (.getFinishedSpanItems exporter))
              span-2 (some #(and (= (.getName %) "test-parallel-2") %) (.getFinishedSpanItems exporter))]
          (is span-1)
          (is span-2)
          (is (= #{"begin" "event: b" "event: d" "event: f"}
                 (into #{} (map #(.getName %) (.getEvents span-1)))))
          (is (= #{"event: a" "event: c" "event: e" "span: after end span"}
                 (into #{} (map #(.getName %) (.getEvents span-2))))))))))
