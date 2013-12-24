(ns confijulate.core
	(:use confijulate.env)
	(:require [clojure.tools.reader.edn :as edn]
						[me.raynes.fs :as fs]))

(def config-heirachy (atom []))

(defn- base-map-from-ns
	[config-ns]
	(->>
	 (ns-interns config-ns)
	 (map #(-> (val %) var-get))
	 (filter #(-> (meta %) :cfj-base))
	 first))

(defn- env-map-from-ns
	[config-ns env]
	(->>
	 (ns-interns config-ns)
	 (map #(-> (val %) var-get))
	 (filter #(-> (meta %) :cfj-env (= env)))
	 first))

(defn- parse-selected-config-file
	[selected-file]
	(when-not (fs/exists? selected-file) (throw (ex-info (str "Cannot find selected configuration file at path " selected-file) {:file selected-file})))
	(let [config-map (edn/read-string (slurp selected-file))]
		(cond
		 (nil? config-map) (throw (ex-info (str "No configuration map can be found in file at path " selected-file) {:file selected-file}))
		 (not (map? config-map)) (throw (ex-info (str "File at path " selected-file " does not contain a map") {:file selected-file}))
		 :else config-map)))


(defn init-ns
	"Initialise an application configuration, using interns defined within the given namespace.
	Looks for maps within the ns with the following metadata:
		- :cfj-base true => the base (default) configuration map. Generally used for local dev environments.
		- :cfj-env \"someValue\" => configuration for the given environment. Will be activated when command line argument cfj-region=someValue

	Additionally, looks for the following system properties:
		- cfj-file => loads map from the file at the given path. The values in this map will take precedence before values in the ns maps

	Returns the seq of config maps in precedence order."
	[config-ns]
	(if-let [config-ns (find-ns config-ns)]
		(let [base-map (base-map-from-ns config-ns)
					selected-file (cfj-file)
					file-map (if selected-file (parse-selected-config-file selected-file) {})
					selected-env (cfj-env)
					env-map (when selected-env (env-map-from-ns config-ns selected-env))]
			(cond
			 (nil? base-map) (throw (ex-info "Cannot find base config map in namespace" {:ns config-ns}))
			 (and selected-env (not env-map)) (throw (ex-info (format "Cannot find env config %s in namespace" selected-env) {:ns config-ns}))
			 :else (swap! config-heirachy conj file-map (if env-map env-map {}) base-map)))

		(ex-info "Cannot find namespace" {:ns config-ns})))


(defn- merge-config-maps
	[config-maps]
	"({:item 1, :second-item 3} {:item 2, :other-item 2})"
	(let [merged-map (apply merge config-maps)
				maps-to-merge (map key (filter #(map? (val %)) merged-map))
				merged-maps (zipmap maps-to-merge (map #(merge-config-maps (map % config-maps)) maps-to-merge))]
			(merge merged-map merged-maps)))


(defn get-cfg
	"Get the first value that matches the given map path defined in kws.
	Searches the config heirachy in the following order:
	1. Specified env config, if any
	2. Base config
	If no value can be found in any config map via the given path, returns nil.
	"
	[& kws]
	(if-let [cfg-val (some #(get-in % kws) @config-heirachy)]
		(if (map? cfg-val)
			(merge-config-maps (reverse (map #(get-in % kws) @config-heirachy)))
			cfg-val)))
