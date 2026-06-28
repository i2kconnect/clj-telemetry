(defproject org.clojars.ejschoen/clj-telemetry "0.5.0-SNAPSHOT"
  :description "A Clojure library designed to wrap OpenTelemetry Java API"
  :url "https://github.com/tendant/clj-telemetry"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [io.opentelemetry/opentelemetry-api "1.62.0"]
                 [io.opentelemetry/opentelemetry-sdk "1.62.0"]
                 [io.opentelemetry/opentelemetry-exporter-otlp "1.62.0"]
                 [io.opentelemetry/opentelemetry-exporter-zipkin "1.62.0"]
                 ;; opentelemetry-exporter-otlp no longer pulls io.grpc transitively
                 ;; (1.62.0 defaults to the OkHttp sender), but compiling against
                 ;; OtlpGrpcSpanExporterBuilder still reflects over its deprecated
                 ;; setChannel(io.grpc.ManagedChannel) overload, so grpc-api must be present.
                 [io.grpc/grpc-api "1.62.2"]
                 ;;[io.grpc/grpc-protobuf "1.34.1"]
                 ;;[io.grpc/grpc-netty-shaded "1.34.1"]
                 ]
  :profiles {:test {:dependencies [[io.opentelemetry/opentelemetry-exporters-inmemory "0.9.1"]
                                   [io.grpc/grpc-protobuf "1.46.0"]
                                   [io.grpc/grpc-netty-shaded "1.46.0"]
                                   ]}}
  :repl-options {:init-ns telemetry.tracing})
