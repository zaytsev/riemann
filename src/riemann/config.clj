(ns riemann.config
  "Riemann config files are eval'd in the context of this namespace. Includes
  streams, client, email, logging, and graphite; the common functions used in
  config. Provides a default core and functions ((tcp|udp)-server, streams,
  index) which modify that core."
  (:require [riemann.core :as core]
            [riemann.service :as service]
            [riemann.transport.tcp        :as tcp]
            [riemann.transport.udp        :as udp]
            [riemann.transport.websockets :as websockets]
            [riemann.transport.sse        :as sse]
            [riemann.transport.graphite   :as graphite]
            [riemann.dashboard :as dashboard]
            [riemann.repl]
            [riemann.index]
            [riemann.logging :as logging]
            [riemann.folds :as folds]
            [riemann.pubsub :as pubsub]
            [riemann.graphite :as graphite-client]
            [riemann.logstash :as logstash-client]
            [clojure.tools.nrepl.server :as repl])
  (:use clojure.tools.logging
        [clojure.java.io :only [file]]
        riemann.client
        riemann.email
        riemann.sns
        [riemann.time :only [unix-time linear-time once! every!]]
        [riemann.pagerduty :only [pagerduty]]
        [riemann.campfire :only [campfire]]
        [riemann.librato :only [librato-metrics]]
        [riemann.nagios :only [nagios]]
        riemann.streams))

(def core "The currently running core."
  (atom (core/core)))
(def next-core "The core which will replace the current core."
  (atom (core/core)))

(def graphite #'graphite-client/graphite)
(def logstash #'logstash-client/logstash)


(defn repl-server
  "Starts a new REPL server with opts."
  [& opts]
  (riemann.repl/start-server (apply hash-map opts)))

(defn service!
  "Ensures that a given service, or its equivalent, is in the next core. If the
  current core includes an equivalent service, uses that service instead.
  Returns the service which will be used in the final core.

  This allows configuration to specify and use services in a way which can,
  where possible, re-use existing services without interruption--e.g., when
  reloading. For example, say you want to use a threadpool executor:

  (let [executor (service! (ThreadPoolExecutor. 1 2 ...))]
    (where (service \"graphite\")
      (on executor
        graph)))

  If you reload this config, the *old* executor is busily processing messages
  from the old set of streams. When the new config evaluates (service! ...)
  it creates a new ThreadPoolExecutor and compares it to the existing core's
  services. If it's equivalent, service! will re-use the *existing*
  executor, which prevents having to shut down the old executor.

  But if you *change* the dynamics of the new executor somehow--maybe by
  adjusting a queue depth or max pool size--they won't compare as equivalent.
  When the core transitions, the old executor will be shut down, and the new
  one used to handle any further graphite events.

  Note: Yeah, this does duplicate some of the work done in core/transition!.
  No, I'm not really sure what to do about it. Maybe we need a named service
  registry so all lookups are dynamic. :-/"
  [service]
  (locking core
    (let [service (or (first (filter #(service/equiv? service %)
                                     (:services @core)))
                      service)]
      (swap! next-core core/conj-service service)
      service)))

(defn instrumentation
  "Replaces the default core's instrumentation service with a new one, using
  the given options. If you prefer not to receive any events about Riemann's
  well-being, you can pass :enabled? false.

  (instrumentation {:interval 5
                    :enabled? false})"
  [& opts]
  (let [service (apply core/instrumentation-service opts)]
    (swap! next-core core/conj-service service :force)
    service))

(defn tcp-server
  "Add a new TCP server with opts to the default core."
  [& opts]
  (service! (tcp/tcp-server (apply hash-map opts))))

(defn graphite-server
  "Add a new Graphite TCP server with opts to the default core."
  [& opts]
  (service! (graphite/graphite-server (apply hash-map opts))))

(defn udp-server
  "Add a new UDP server with opts to the default core."
  [& opts]
  (service! (udp/udp-server (apply hash-map opts))))

(defn ws-server
  "Add a new websockets server with opts to the default core."
  [& opts]
  (service! (websockets/ws-server (apply hash-map opts))))

(defn sse-server
  "Add a new SSE channel server with opts to the default core."
  [& opts]
  (service! (sse/sse-server (apply hash-map opts))))

(defn dashboard-server
  "Add a new Dashboard server with opts to the default core."
  [& opts]
  (service! (dashboard/dashboard-server (apply hash-map opts))))

(defn streams
  "Add any number of streams to the default core."
  [& things]
  (locking core
    (swap! next-core assoc :streams
           (reduce conj (:streams @next-core) things))))

(defn index
  "Set the index used by this core. Returns the index."
  [& opts]
  (let [index (apply riemann.index/index opts)]
    (locking core
      (swap! next-core assoc :index index))
    index))

(defn update-index
  "Updates the given index with all events received. Also publishes to the
  index pubsub channel."
  [index]
  (fn update [event] (core/update-index @core event)))

(defn delete-from-index
  "Deletes any events that pass through from the index. By default, deletes
  events with the same host and service. If a field, or a list of fields, is
  given, deletes any events with matching values for all of those fields.

  ; Delete all events in the index with the same host
  (delete-from-index :host event)

  ; Delete all events in the index with the same host and state.
  (delete-from-index [:host :state] event)"
  ([]
     (fn delete [event] (core/delete-from-index @core event)))
  ([fields]
     (fn delete [event] (core/delete-from-index @core fields event))))

(defn periodically-expire
  "Sets up a reaper for this core. See riemann.core/reaper."
  ([]
     (periodically-expire 10))
  ([& args]
     (service! (apply core/reaper args))))

(defn async-queue!
  "A stream which registers (using service!) a new threadpool-service with the
  next core, and returns a stream which accepts events and applies those events
  to child streams via the threadpool service.

  WARNING: this function is not intended for dynamic use. It creates a new
  executor service for *every* invocation. It will not start the executor
  service until the current configuration is applied. Use sparingly and only at
  configuration time--preferably once for each distinct IO-bound asynchronous
  service.

  Example:

  (let [graph (async-queue! :graphite {:queue-size 100}
                            (graphite {:host ...}))]
    (streams
      (where ... graph)))"
  [name threadpool-service-opts & children]
  (let [s (service! (service/threadpool-service name threadpool-service-opts))]
    (apply execute-on s children)))

(defn publish
  "Returns a stream which publishes events to the given channel. Uses this
  core's pubsub registry."
  [channel]
  (fn [event]
    (pubsub/publish! (:pubsub @core) channel event)))

(defn subscribe
  "Subscribes to the given channel with f, which will receive events. Uses the
  current core's pubsub registry always, because the next core's registry will
  be discarded by core/transition.

  Returns a single-arity function that does nothing with its inputs and, when
  invoked, returns the subscription you created. Why do this weird thing? So
  you can pretend (subscribe ...) is a stream, and use it in the same context
  as your other streams, like (publish)."
  [channel f]
  (let [sub (pubsub/subscribe! (:pubsub @core) channel f)]
    (fn discard [event] sub)))

(defn clear!
  "Resets the next core."
  []
  (locking core
    (reset! next-core (core/core))))

(defn apply!
  "Applies pending changes to the core. Transitions the current core to the
  next one, and resets the next core."
  []
  (locking core
    (swap! core core/transition! @next-core)
    (clear!)))

(defn start!
  "Start the current core."
  []
  (core/start! @core))

(defn stop!
  "Stop the current core."
  []
  (core/stop! @core))

(defn read-strings
  "Returns a sequence of forms read from string."
  ([string]
     (read-strings []
                   (-> string (java.io.StringReader.)
                       (clojure.lang.LineNumberingPushbackReader.))))
  ([forms reader]
     (let [form (clojure.lang.LispReader/read reader false ::EOF false)]
       (if (= ::EOF form)
         forms
         (recur (conj forms form) reader)))))

(def ^:dynamic *config-file*
  "The config file currently being included."
  nil)

(defn config-file-path
  "Computes the full path to a config file. Absolute paths are returned
  unchanged. Relative paths are expanded relative to *config-file*. Returns a
  string."
  [path]
  (if (-> path (file) (.isAbsolute))
    path
    (let [dir (-> (or *config-file* "ZARDOZ")
                  (file)
                  (.getCanonicalPath)
                  (file)
                  (.getParent))]
      (str (file dir path)))))

(defn validate-config
  "Check that a config file has valid syntax."
  [file]
  (try
    (read-strings (slurp file))
    (catch clojure.lang.LispReader$ReaderException e
      (throw (logging/nice-syntax-error e file)))))

(defn include
  "Include another config file or directory. If the path points to a
   directory, all files within it will be loaded recursively.

  ; Relative to the current config file, or cwd
  (include \"foo.clj\")

  ; Absolute path
  (include \"/foo/bar.clj\")"
  [path]
  (let [path (config-file-path path)
        file (file path)]
    (binding [*config-file* path
              *ns* (find-ns 'riemann.config)]
      (if (.isDirectory file)
        (doseq [f (file-seq file)]
          (when (.isFile f)
            (load-file (.toString f))))
        (load-file path)))))
