(ns active.clojure.config
  "Application configuration via a big map.

A configuration is a nested key-value map.

It contains top-level settings and sections of qualified settings, like so:


    {:top-level-setting-key 'foo
     {:section-key
      {:section-setting 'bar}}}

Additionally, a config contains *profiles* with additional
settings that can be mixed in, like so:

    {:top-level-setting-key 'foo
     {:section-key
      {:section-setting 'bar}}
     {:profiles
      {:dev
       {:top-level-setting-key 'bar
        {:section-key
         {:section-setting 'baz}}}}}}

Each profile has the same format as the top-level configuration itself
  (sans the `:profiles` key)."
  #?(:cljs (:require-macros [active.clojure.record :refer (define-record-type)]))
  (:require [clojure.set :as set]
            [active.clojure.condition :as c]
            #?(:cljs [active.clojure.record])
            #?(:clj [active.clojure.record :refer :all]))
  #?(:clj (:import [java.net URL])))

(define-record-type 
  ^{:doc "Description of a range of values."}
  ValueRange ; used to be called Range, but conflicts with cljs.core/->Range
  (^{:doc "Make a [[Range]] range object.
  - `description' is a human-readable string
  - `completer' is a function that accepts a range, a key, and a value,
     and either returns a \"completed\" value that takes defaults etc. into account,
     or a [[RangeError]] object.
  - `reduce` is a function accepting a range, a key, a function, an initial result,
     and a value, reducing collection values similar to clojure [[reduce]], calling
     `(f range key res v)` on all scalar values."
     }
   make-range description completer reduce)
  range?
  [description range-description
   ;; takes the range, a key that says where the value was found, and the value
   ;; returns either a "completed" value or a range error
   completer range-completer
   reduce range-reduce])

(define-record-type
  ^{:doc "Description of an error that occurred during range checking"}
  RangeError
  (^{:doc "Make a a [[RangeError]] object describing an error from range checking.
  - `range` is the range that caused the error
  - `path` is the path in the configuration that describes where the error is.
  - `value` is the value that was wrong.

  `range` can be `nil' if `key` does not appear in the schema."}
   make-range-error range path value)
  range-error?
  [range range-error-range
   path range-error-path
   value range-error-value])

(defn make-scalar-range
  "Make a range for unstructured, non-collection ranges."
  [description completer]
  (make-range description completer
              (fn [range path f res val]
                (let [v' (completer range path val)]
                  (assert (not (range-error? v')) (pr-str v'))
                  (f range path res v')))))

(defn any-value-range
  "Range for any value at all."
  [dflt]
  (make-scalar-range "any value at all"
                     (fn [range path val]
                       (if (nil? val)
                         dflt
                         val))))

(def non-nil-range
  "Range for any non-nil value."
  (make-scalar-range "non-nil value"
                     (fn [range path val]
                       (if (some? val)
                         val
                         (make-range-error range path val)))))

(defn predicate-range
  "Range specified by a simple predicate."
  [desc pred dflt]
  (make-scalar-range desc
                     (fn [range path val]
                       (cond
                         (nil? val) dflt
                         (pred val) val
                         :else (make-range-error range path val)))))

(defn boolean?
  "Check if a value is a boolean."
  [x]
  (or (= x true)
      (= x false)))

(defn boolean-range
  "Range for a boolean, with explicit default."
  [dflt]
  (predicate-range
   "true or false" boolean? dflt))

(defn optional-range
  "Range for something that may be in an underlying range or `nil`."
  [range]
  (make-range (str (range-description range) " (optional)")
              (fn [this-range path val]
                (if (nil? val)
                  nil
                  ((range-completer range) range path val)))
              (fn [this-range path f res val]
                (if (nil? val)
                  (f range path res nil) ;; or just res?
                  ((range-reduce range) range path f
                   res val)))))

(defn optional-default-range
  "Range for something that may be in an underlying range. If it is nil, then `dflt` is used, which must be in the underlying range too."
  [range dflt]
  (make-range (str (range-description range) " (optional with default)")
              (fn [this-range path val]
                ((range-completer range) range path
                 (if (nil? val)
                   dflt
                   val)))
              (fn [this-range path f res val]
                ((range-reduce range) range path f
                 res (if (nil? val)
                       dflt
                       val)))))

(defn integer-between-range
  "Range for an integer from a specified range, with explicit default."
  [min max dflt]
  (make-scalar-range (str "integer between " min " and " max)
                     (fn [range path val]
                       (cond
                         (nil? val) dflt

                         (and (integer? val)
                              (>= val min)
                              (<= val max))
                         val

                         :else (make-range-error range path val)))))

(def keyword-range
  (make-scalar-range "keyword"
                     (fn [range path val]
                       (if (keyword? val)
                         val
                         (make-range-error range path val)))))

(defn default-string-range
  [dflt]
  "Range for an abitrary string, default is `dflt`."
  (make-scalar-range "string"
                     (fn [range path val]
                       (cond
                         (nil? val) dflt
                         (string? val) val
                         :else (make-range-error range path val)))))

(def string-range
  "Range for an abitrary string, default is empty string."
  (default-string-range ""))

(defn nonempty-string-range
  "Range for a non-empty string with optional max length."
  [& [max-length]]
  (make-scalar-range (str "non-empty string" (if (some? max-length) 
                                               (str " with maximum length of " max-length)
                                               ""))
                     (fn [range path val]
                       (if (and (string? val) (not (empty? val)) 
                                (if (some? max-length) (<= (count val) max-length) true))
                         val
                         (make-range-error range path val)))))

(defn max-string-range [max-length]
  "Range for an arbitrary string with maximum length."
  (make-scalar-range (str "Arbitrary string with maximum length of " max-length)
                     (fn [range path val]
                       (if (and (string? val)
                                (<= (count val) max-length))
                         val
                         (make-range-error range path val)))))

(defn any-range
  "Range that satisfies one of the ranges, tried from left to right."
  [& rs]
  (make-range (apply str "any:" (map #(str " <" (range-description %) ">") rs))
              (fn [range path val]
                (loop [rs rs]
                  (if (empty? rs)
                    (make-range-error range path val)
                    (let [res ((range-completer (first rs)) (first rs) path val)]
                      (if (range-error? res)
                        (recur (rest rs))
                        res)))))
              (fn [range path f res val]
                (loop [rs rs]
                  (if (empty? rs)
                    (assert false)
                    (let [this-range (first rs)
                          v ((range-completer this-range) this-range path val)]
                      (if (range-error? v)
                        (recur (rest rs))
                        ((range-reduce this-range) this-range path f res v))))))))

(defn one-of-range
  "Range for one of a set of values, with explicit default."
  [vals dflt]
  (make-scalar-range (apply str "one of:"
                            (map #(str " " %) vals))
                     (let [s (set vals)]
                       (fn [range path val]
                         (cond
                           (nil? val) dflt

                           (contains? s val) val

                           :else (make-range-error range path val))))))

(defn one-of-range-custom-compare
  "Range for one of a set of values, with custom compare function,
  with explicit default."
  [vals dflt compare-fn]
  (make-scalar-range (apply str "one of:"
                            (map #(str " " %) vals))
                     (let [s (set vals)]
                       (fn [range path val]
                         (cond
                           (nil? val) dflt

                           (some #(compare-fn val %) s) val

                           :else (make-range-error range path val))))))

;; Argl ...
(defn sequable?
  "Test if something can be coerced to a seq."
  [thing]
  (try
    (seq thing)
    true
    (catch #?(:clj Throwable) #?(:cljs js/Error) e
      false)))

(defn sequence-of-range
  "Range for a sequence of values of an underlying range."
  [range]
  (make-range (str "sequence of " (range-description range))
              (let [complete (range-completer range)]
                (fn [this-range path val]
                  (cond
                   (nil? val) []

                   (not (sequable? val)) (make-range-error this-range path val)

                   :else
                   (loop [i 0
                          vals (seq val)
                          ret []]
                     (if vals
                       (let [res (complete range (conj path i) (first vals))]
                         (if (range-error? res)
                           res
                           (recur (inc i)
                                  (next vals)
                                  (conj ret res))))
                       ret)))))
              (fn [this-range path f res val]
                (let [v ((range-completer this-range) this-range path val)]
                  (assert (not (range-error? v)) (pr-str v))
                  (reduce (fn [res [i x]]
                            ((range-reduce range) range (conj path i) f res x))
                          res
                          (map-indexed vector v))))))

(defn range-map
  "Range constructed by transforming values matching an existing range."
  [descr range f & args]
  (make-range descr
              (let [complete (range-completer range)]
                (fn [this-range path val]
                  (let [res (complete this-range path val)]
                    (if (range-error? res)
                      res
                      (apply f res args)))))
              (range-reduce range) ;; ?? TODO correct?
              ))

(defn set-of-range
  "Range for a set of values of an underlying range."
  [range]
  (range-map (str "set of " (range-description range))
             (sequence-of-range range)
             set))

(defn tuple-of-range
  "Range for a sequence of mixed underlying ranges."
  [& rs]
  (make-range (apply str "tuple of: " (interpose ", " (map range-description rs)))
              (fn [this-range path val]
                (cond
                  (sequable? val)
                  (let [res (map-indexed (fn [i [v range]]
                                           ((range-completer range) range (conj path i) v))
                                         (map vector
                                              (seq val)
                                              rs))]
                    (or (some #(and (range-error? %) %) res)
                        (vec res)))

                  :else
                  (make-range-error this-range path val)))
              (fn [this-range path f res val]
                (let [v ((range-completer this-range) this-range path val)]
                  (assert (not (range-error? v)) (pr-str v))
                  (reduce (fn [res [i [v range]]]
                            ((range-reduce range) range (conj path i)
                             f res v))
                          res
                          (map-indexed vector (map vector v rs)))))
              ))

(defn map-of-range
  "Range for a map with keys and values of underlying ranges, respectively."
  [key-range val-range]
  (make-range (str "map from " (range-description key-range) " to " (range-description val-range))
              (let [complete-val (range-completer val-range)
                    complete-key (range-completer key-range)]
                (fn [this-range ky vl] ; we need the key & val functions
                  (cond
                   (nil? vl) {}
                   
                   (map? vl)
                   (loop [kvs (seq vl)
                          ret {}]
                     (if kvs
                       (let [kv (first kvs)
                             p (conj ky (key kv))
                             k (complete-key key-range p (key kv))
                             v (complete-val val-range p (val kv))]
                         (cond
                          (range-error? k) k
                          (range-error? v) v
                          :else (recur (next kvs)
                                       (assoc ret k v))))
                       ret))
                   
                   :else (make-range-error this-range ky vl))))
              (fn [this-range path f res val]
                (let [v ((range-completer this-range) this-range path val)]
                  (assert (not (range-error? v)) (pr-str v))
                  (reduce (fn [res [k v]]
                            ((range-reduce val-range) val-range (conj path k) f
                             ((range-reduce key-range) key-range path f res k)
                             v))
                          res
                          v)))
              ))

#?(:clj (def slurpable-range
  "Range for something that may be passed to [[slurp]]."
          (make-scalar-range "Slurpable"
                             (fn [range path val]
                               (cond
                                 (instance? URL val) val
                                 (string? val) val
                                 ;; FIXME: more cases?
                                 :else (make-range-error range path val))))))
                 

;; Schemas

(define-record-type
  ^{:doc "Named setting within a config."}
  Setting
  (^{:doc "Make a named schema [[Setting]] object.
  - `key` is a keyword naming the setting
  - `description` is a human-readable description object
  - `range` is a [[Range]] for the admissible values of the setting
  - `inherit?` says whether the setting values may be inherited from a surrounding section"}
   make-setting key description range inherit?)
  setting?
  [key setting-key
   description setting-description
   range setting-range
   inherit? setting-inherit?])

(defn setting
  "Construct a setting.
  - `key` is a keyword naming the setting
  - `description` is a human-readable description object
  - `range` is a [[Range]] for the admissible values of the setting
  - `inherit?` says whether the setting values may be inherited from a surrounding section"
  [key description range & {:keys [inherit?]}]
  {:pre [(string? description)]}
  (make-setting key
                description
                range
                (if inherit? true false)))

(defn setting-default-value
  "Compute the default value for a setting."
  [setting]
  (let [range (setting-range setting)
        val ((range-completer range) range
             [(setting-key setting)]
             nil)]
    (if (range-error? val)
      (c/error `setting-default-value
               "setting is missing in configuration map"
               (range-error-path val)
               (range-error-value val)
               (range-description (range-error-range val))
               (range-error-range val))
      val)))

(define-record-type
  ^{:doc "Section within a config with settings of its own."}
  Section
  (make-section key schema inherit?)
  section?
  [^{doc "keyword naming the section"}
   key section-key

   ^{doc "sub-schema describing the section's format"}
   schema section-schema

   ^{doc "whether this section inherits from outer levels"}
   inherit? section-inherit?])

(defn section
  "Make a section within a config with settings of its own."
  [key schema & {:keys [inherit?]}]
  (make-section key schema (if inherit? true false)))

(define-record-type
  ^{:doc "A schema describes a config format, and can be used for
  validation and completion."}
  Schema
  (^{:doc "Make a [[Schema]] object describing a config format.
  *For internal use;* you should use [[schema]].

  - `description` is a human-readable description
  - `settings` is a collection of [[Setting]]s
  - `settings-map` is a map from setting keys to settings
  - `sections` is a collection of [[Section]]s
  - `sections-map` is a map from section keys to sections"}
   make-schema description settings settings-map sections sections-map)
  schema?
  [description schema-description
   settings schema-settings
   settings-map schema-settings-map
   sections schema-sections
   sections-map schema-sections-map])

(declare normalize&check-config-map)

(declare schema-range)

(defn- schema-reduce [schema range path f res val]
  (let [cmap ((range-completer range) range path val)
        settings (schema-settings-map schema)
        sections (schema-sections-map schema)]
    (assert (not (range-error? cmap)) (pr-str cmap))
    (reduce (fn [res [k v]]                            
              (if-let [setting (get settings k)]
                ((range-reduce (setting-range setting))
                 (setting-range setting)
                 (conj path (setting-key setting))
                 f res v)
                ;; if not a setting, it must be a section:
                (let [section (get sections k)]
                  (schema-reduce (section-schema section)
                                 (schema-range (section-schema section))
                                 (conj path (section-key section))
                                 f res v))))
            res
            cmap)))

(defn schema-range
  "Range for a configuration object matching a schema."
  [schema]
  (make-range (schema-description schema)
              (fn [range path val]
                (cond
                  (nil? val) (normalize&check-config-map schema [] {})
                  (map? val) (normalize&check-config-map schema [] val)
                  :else (make-range-error range path val)))
              (partial schema-reduce schema)))

; Note that a global setting which can be overridden locally needs to
; be listed both at the top level and within the section.

; FIXME: check that they're both identical

(defn schema
  "Construct a schema.

  - `description` is a human-readable description
  - `element-list' is a list of the [[Setting]]s and [[Section]]s of the schema"
  [description & element-list]
  {:pre [(string? description)]}
  ;; FIXME: should make sure there are no duplicates
  (let [settings (filter setting? element-list)
        sections (filter section? element-list)]
    (make-schema
     description
     (set settings)
     (zipmap (map setting-key settings) settings)
     (set sections)
     (zipmap (map section-key sections) sections))))

(defn- merge-config-maps-sans-profiles
  "Merge two config maps into one, with the latter taking precedence.

  This helper assumes there are no profiles."
  [schema path c1 c2]
  (when-not (map? c1)
    (c/error `merge-config-maps-sans-profiles
             "configuration is not a map" path c1))
  (when-not (map? c2)
    (c/error `merge-config-maps-sans-profiles
             "configuration is not a map" path c2))
  (let [sections-map (schema-sections-map schema)
        settings-map (schema-settings-map schema)]
    (loop [c {}
           all-keys (seq (set/union (set (keys c1))
                                    (set (keys c2))))]
      (if all-keys
        (let [key (first all-keys)
              val1 (get c1 key)
              val2 (get c2 key)]
          (if (contains? settings-map key)
            (recur (assoc c 
                     ;; that `nil` is a valid value
                     key 
                     (if (contains? c2 key)
                       val2
                       val1))
                   (next all-keys))
            (if-let [section (get sections-map key)]
              (recur (assoc c key 
                            (merge-config-maps-sans-profiles (section-schema section) (conj (vec path) key) (or val1 {}) (or val2 {})))
                     (next all-keys))
              (c/error `merge-config-maps-sans-profiles
                       "unknown path in config"
                       (conj path key) (if (contains? c1 key) val1 val2) nil (if (contains? c1 key) c1 c2)))))
        c))))

(defn merge-config-maps
  "Merge several config maps into one, with the latter taking precedence."
  ([schema c] c)
  ([schema c1 c2]
     (let [profiles-1 (get c1 :profiles)
           profiles-2 (get c2 :profiles)]
       (assoc (merge-config-maps-sans-profiles schema []
                                               (dissoc c1 :profiles)
                                               (dissoc c2 :profiles))
         :profiles (merge profiles-1 profiles-2))))
  ([schema c1 c2 & cs] ; Clojure won't let us do [schema c1 & cs]
     (reduce (partial merge-config-maps schema) (concat [c1 c2] cs))))

(defn- apply-profiles
  "Apply named profiles within a config map."
  [schema config-map profile-names]
  (if-let [profile-map (:profiles config-map)]
    (let [config-map (dissoc config-map :profiles)
          profiles (map (fn [n]
                          (or (profile-map n)
                              (c/error `apply-profiles "profile does not exist" n)))                              
                        profile-names)]
      (reduce (partial merge-config-maps-sans-profiles schema []) config-map profiles))
    config-map))

(defn normalize&check-config-map
  "Normalize and check the validity of a config-map.

  In  the result, every setting has an associated value."
  ([schema profile-names config-map]
     (normalize&check-config-map schema profile-names config-map {} []))

  ([schema profile-names config-map inherited-map path]
     (let [config-map (apply-profiles schema config-map profile-names)]
       (letfn [(complete-settings
                 [inherited-map settings-map]
                 (zipmap (keys settings-map)
                         (map (fn [setting]
                                (or (get inherited-map (setting-key setting))
                                    (setting-default-value setting)))
                              (vals settings-map))))
               (complete-section
                 [inherited-map section]
                 (let [key (section-key section)]
                   (if-let [entry (get inherited-map key)]
                     {key entry}
                     (let [res
                           (normalize&check-config-map (section-schema section) profile-names {}
                                                       inherited-map (concat path [key]))]
                       (assert (not (range-error? res)) (pr-str res))
                       {key res}))))]

         (let [sections-map (schema-sections-map schema)
               res
               ;; go through the settings first, as we need to collect
               ;; the inherited settings
               (loop [entries (seq config-map)
                      c {}
                      inherited-map inherited-map
                      settings-map (schema-settings-map schema)]
                 (if entries
                   (let [[key val] (first entries)]
                     (if-let [setting (get settings-map key)]
                       (let [range (setting-range setting)
                             res ((range-completer range) range (concat path [key]) val)]
                         (if (range-error? res)
                           res
                           (recur (next entries)
                                  (assoc c key res)
                                  (if (setting-inherit? setting)
                                    (assoc inherited-map key val)
                                    inherited-map)
                                  (dissoc settings-map key))))
                       (if (contains? sections-map key) ; do sections later
                         (recur (next entries) c inherited-map settings-map)
                         (make-range-error nil (concat path [key]) val))))
                   [(merge c (complete-settings inherited-map settings-map))
                    inherited-map settings-map]))]
           (if (range-error? res)
             res
             (let [[c inherited-map settings-map] res]
               ;; now go through the sections
               (loop [entries (seq config-map)
                      c c
                      inherited-map inherited-map
                      sections-map sections-map]
                 (if entries
                   (let [[key val] (first entries)]
                     (if-let [section (get sections-map key)]
                       (let [res (normalize&check-config-map (section-schema section)
                                                             profile-names
                                                             val
                                                             inherited-map
                                                             (concat path [key]))]
                         (if (range-error? res)
                           res
                           (recur (next entries)
                                  (assoc c key res)
                                  (if (section-inherit? section)
                                    (assoc inherited-map key res)
                                    inherited-map)
                                  (dissoc sections-map key))))
                       (recur (next entries) c inherited-map sections-map)))
                   (apply merge c (map (partial complete-section inherited-map) (vals sections-map))))))))))))

(define-record-type ^{:doc "Validated and expanded configuration object."}
  Configuration
  (really-make-configuration map schema)
  configuration?
  [map configuration-map
   schema configuration-schema])

(defn make-configuration
  "Make a configuration from a map."
  [schema profile-names config-map]
  (let [res (normalize&check-config-map schema profile-names config-map)]
    (if (range-error? res)
      (c/error `make-configuration
               "range error in configuration map"
               (range-error-path res)
               (range-error-value res)
               (range-description (range-error-range res))
               (range-error-range res))
      (really-make-configuration res schema))))

(defn diff-configuration-maps
  "Returns sequence of triples `[path-vector version-1 version-2]` of settings that differ.

  The config maps must be validated and completed."
  [schema config-map-1 config-map-2]
  (concat (filter identity
                  (map (fn [[key _]]
                         (let [v1 (get config-map-1 key)
                               v2 (get config-map-2 key)]
                           (and (not= v1 v2)
                                [[key] v1 v2])))
                       (schema-settings-map schema)))
          (mapcat (fn [[key section]]
                    (map (fn [[path v1 v2]]
                           [(vec (cons key path)) v1 v2])
                         (diff-configuration-maps (section-schema section) 
                                                  (get config-map-1 key) (get config-map-2 key))))
                  (schema-sections-map schema))))
    
(defn diff-configurations
  "Returns sequence of triples [path-vectors version-1 version-2] of settings that differ."
  [schema config-1 config-2]
  (diff-configuration-maps schema
                           (configuration-map config-1)
                           (configuration-map config-2)))

(defn access-section
  "Access the settings of a section."
  [config & sections]
  (let [val (get-in (configuration-map config)
                    (map section-key sections)
                    :section-not-found)]
    (if (= val :section-not-found)
      (c/assertion-violation `access-section
                             "section not found"
                             (map section-key sections) config)
      val)))

(defn access
  "Access the value of a setting.

  Note that the setting comes first, followed by the access path."
  [config setting & sections]
  (let [sct (apply access-section config sections)
        val (get sct (setting-key setting) :setting-not-found)]
    (if (= val :setting-not-found)
      (c/assertion-violation `access
                             "setting not found"
                             (setting-key setting) (map section-key sections) setting config)
      val)))

(defn reduce-scalar-config-settings
  "Reduce over all scalary config values in config, where `f` is
  called as `(f range key init val)`, where `val` matches the scalary
  `range`."
  [schema f init config-map]
  (let [r (schema-range schema)]
    ((range-reduce r) r [] f init config-map)))

(defn or-dot-range
  "Given range `r` is required in the first line,
the remainder of the lines the field holds \".\"."
  [r]
  (any-range (one-of-range #{"."} nil) r))
