(ns tool.core
  (:require
    [clojure.string :as string]
    [cljs.pprint :refer [pprint]]
    [tool.io :as io]))

;; config data
(def config nil)
(def deps-cache nil)

(def dep-keys [:dependencies
               :dev-dependencies])

;; filenames
(def file-config-edn "cljs.edn")
(def file-config-json "cljs.json")
(def file-deps-cache ".deps-cache.edn")
(def file-dep-retriever (str js/__dirname "/../dep-retriever/target/dep-retriever-0.1.0-standalone.jar"))
(def file-build (str js/__dirname "/../script/build.clj"))
(def file-watch (str js/__dirname "/../script/watch.clj"))
(def file-repl (str js/__dirname "/../script/repl.clj"))

(def dir-cljs-jars (str js/__dirname "/../cljs-jars/"))
(defn file-cljs-jar [version] (str dir-cljs-jars version ".jar"))
(defn url-cljs-jar [version] (str "https://github.com/clojure/clojurescript/releases/download/r" version "/cljs.jar"))

;;---------------------------------------------------------------------------
;; Misc
;;---------------------------------------------------------------------------

(def child-process (js/require "child_process"))
(def spawn-sync (.-spawnSync child-process))
(def exec-sync (.-execSync child-process))

(defn exit-error [& args]
  (apply js/console.error args)
  (js/process.exit 1))

;;---------------------------------------------------------------------------
;; Validation
;;---------------------------------------------------------------------------

(defn java-installed? []
  (not (.-error (spawn-sync "java"))))

(defn ensure-java! []
  (or (java-installed?)
      (exit-error "Please install Java.")))

(defn ensure-config! []
  (or (io/slurp-edn file-config-edn)
      (io/slurp-json file-config-json)
      (exit-error "No config found. Please create one in" file-config-edn "or" file-config-json)))

(defn ensure-cljs-version! []
  (let [version (:cljs-version config)
        jar-path (file-cljs-jar version)
        jar-url (url-cljs-jar version)]
    (or (io/path-exists? jar-path)
        (do (println "Downloading ClojureScript version" version)
            (io/mkdirs dir-cljs-jars)
            (io/download jar-url jar-path)))))

(defn ensure-cmd! [id]
  (or (get-in config [:scripts (keyword id)])
      (exit-error (str "Unrecognized command: '" id "' is not found in :scripts map"))))

(defn ensure-build! [id]
  (or (get-in config [:builds (keyword id)])
      (exit-error (str "Unrecognized build: '" id "' is not found in :builds map"))))

(declare task-install)

(defn ensure-dependencies! []
  (let [cache (io/slurp-edn file-deps-cache)
        stale? (not= (select-keys config dep-keys)
                     (select-keys cache dep-keys))]
    (if stale?
      (task-install)
      cache)))

;;---------------------------------------------------------------------------
;; Main Tasks
;;---------------------------------------------------------------------------

(defn task-install []
  (ensure-java!)
  (let [deps (apply concat (map config dep-keys))
        result (spawn-sync "java"
                 #js["-jar" file-dep-retriever (pr-str deps)]
                 #js{:stdio #js["pipe" "pipe" 2]})
        stdout-lines (when-let [output (.-stdout result)]
                       (string/split (.toString output) "\n"))
        success? (and (zero? (.-status result))
                      (not (.-error result)))]
    (when success?
      (let [cache (-> config
                      (select-keys dep-keys)
                      (assoc :jars stdout-lines))]
        (io/spit file-deps-cache (with-out-str (pprint cache)))
        cache))))

(defn build-classpath [{:keys [src] :as build}]
  (let [{:keys [jars]} (ensure-dependencies!)
        source-paths (when src (if (sequential? src) src [src]))
        cljs-jar (file-cljs-jar (:cljs-version config))
        all (concat [cljs-jar] jars source-paths)]
    (string/join ":" all)))

(defn task-script [id file-script]
  (ensure-java!)
  (let [build (ensure-build! id)]
    (spawn-sync "java"
      #js["-cp" (build-classpath build) "clojure.main" file-script (pr-str build)]
      #js{:stdio "inherit"})))

(defn task-repl [id]
  (ensure-java!)
  (let [build (when id (ensure-build! id))]
    (spawn-sync "java"
      #js["-cp" (build-classpath build) "clojure.main" file-repl]
      #js{:stdio "inherit"})))

(defn custom-script [id]
  (exec-sync (ensure-cmd! id) #js{:stdio "inherit"}))

(defn -main [task id]
  (set! config (ensure-config!))
  (ensure-cljs-version!)
  (cond
    (= task "install") (task-install)
    (= task "build") (task-script id file-build)
    (= task "watch") (task-script id file-watch)
    (= task "repl") (task-repl id)
    :else (custom-script task)))

(set! *main-cli-fn* -main)
(enable-console-print!)