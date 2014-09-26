(ns ^{:doc "Conditions, protocol for communicating the causes of exceptions.

  This provides infrastructure for building *condition* objects.
  A condition object provides information about the cause of an exception.
  Conditions thus form a protocol.

  Clojure `ex-info` objects do not, by themselves, enough to form such
  a protocol, as they do not allow classifying an exception easily.

  This condition system builds on the design of
  [R6RS Scheme](http://www.r6rs.org/final/html/r6rs-lib/r6rs-lib-Z-H-8.html#node_sec_7.2).

  Condition objects are represented as specially marked `ex-info` objects.

  One notable differnce to the R6RS design is that there is no user-facing type for 
  'simple conditions', nor are they regular records."}
  active.clojure.condition
  (:refer-clojure :exclude (assert))
  (:require [clojure.core :as core]) ; get assert back
  (:require [clojure.stacktrace :as stack])
  (:import clojure.lang.ExceptionInfo))

(defn condition?
  [x]
  (and (instance? ExceptionInfo x)
       (contains? (ex-data x) ::condition)))

(declare print-condition)

(defmethod print-method ExceptionInfo [^ExceptionInfo exc ^java.io.Writer w]
  (if (condition? exc)
    (print-condition exc w)
    (.write w (str "clojure.lang.ExceptionInfo: " (.getMessage exc) " " (str (ex-data exc))))))

(def ^:private ex-info-msg (str "This is a " *ns* " util.condition."))

(defrecord ConditionType
    [name supertype field-names total-field-count])

(def ^{:doc "Root of the condition-type hierarchy."}
  &condition
  (ConditionType. '&condition nil [] 0))

(defrecord ConditionComponent
    [type arguments])

(defn- condition-component?
  "Is object a condition component?"
  [x]
  (instance? ConditionComponent x))

(defn- condition-subtype?
  "Is `ty1` subtype of `ty2`?"
  [ty1 ty2]
  (or (= ty1 ty2)
      (if-let [st (:supertype ty1)]
        (condition-subtype? st ty2)
        false)))

(defn- condition-component-of-condition-type?
  "Does condition component `comp` have condition type `type`?"
  [type comp]
  (condition-subtype? (:type comp) type))

(defn- condition-components
  "Extract components from condition."
  [cond]
  (::components (ex-data cond)))

(defn make-condition
  "Make a condition from components.

  For internal use only."
  [condition-components]
  (ex-info ex-info-msg
           {::condition true
            ::components condition-components}))

(defn combine-conditions
  "Make a compound condition from constituents."
  [& component-conditions]
  (make-condition (mapcat condition-components component-conditions)))

(defn condition-of-type?
  "Does condition `cond` have type `type`?"
  [type cond]
  (some (partial condition-component-of-condition-type? type) (::components (ex-data cond))))

(defn condition-predicate
  "Make predicate from condition type."
  [type]
  (fn [x]
    (and (condition? x)
         (condition-of-type? type x))))

(defn- field-index
  "Compute the field index of field named `field-name` for type `type`."
  [type field-name]
  (let [^java.util.List fn (:field-names type)
        local-index (.indexOf fn field-name)]
    (core/assert (not= -1 local-index))
    (if-let [supertype (:supertype type)]
      (+ (:total-field-count supertype)
         local-index)
      local-index)))

(defn condition-accessor
  "Create an an accessor for `field-name` for conditions of type `type`."
  [type field-name]
  (let [i (field-index type field-name)]
    (fn [cond]
      {:pre [(condition? cond)
             (condition-of-type? type cond)]}
      (if-let [comp
               (some (fn [comp]
                       (and (condition-component-of-condition-type? type comp)
                            comp))
                     (::components (ex-data cond)))]
        (nth (:arguments comp) i)
        (throw (Error. (str cond " is not a condition of type " type)))))))

(defmacro define-condition-type
  "    (define-condition-type <condition-type>
        <supertype>
        <constructor> <predicate>
        <field-spec1> ...)

  `<Condition-type>`, `<supertype>`, `<constructor>`, and
  `<predicate>` must all be identifiers. Each `<field-spec>` must be of
  the form `(<field> <accessor>)` where both `<field>` and `<accessor>`
  must be identifiers.

  The `define-condition-type` form defines a condition type named
  `<condition-type>`.  It will have <supertype> has its parent type. The
  remaining identifiers will be bound as follows:

  `<Constructor>` is bound to a constructor for the type: It accepts
  one argument for each of the condition type's complete set of fields
  (including parent types, with the fields of the parent coming before
  those of the extension in the arguments) and returns a condition
  object initialized to those arguments.

  `<Predicate>` is bound to a predicate that identifies conditions of
  type `<condition-type>` or any of its subtypes.

  Each `<accessor>` is bound to a procedure that extracts the
  corresponding field from a condition of type `<condition-type>`."
  [?condition-type ?supertype ?constructor ?predicate & ?field-specs]
  `(do
     (let [total-field-count# (+ ~(count ?field-specs) (:total-field-count ~?supertype))]
       (def ~?condition-type
         (ConditionType. '~?condition-type ~?supertype 
                         '[~@(map first ?field-specs)] total-field-count#))
       (defn ~?constructor
         [& args#]
         {:pre [(= (count args#) total-field-count#)]}
         (make-condition [(ConditionComponent. ~?condition-type (vec args#))])) 
       (def ~?predicate (condition-predicate ~?condition-type))
       ~@(map (fn [[?field-name ?accessor]]
                `(def ~?accessor (condition-accessor ~?condition-type '~?field-name)))
              ?field-specs))))

; These standard condition types correspond directly to R6RS Scheme

(define-condition-type &message &condition
  make-message-condition message-condition?
  (message condition-message))

(define-condition-type &warning &condition
  make-warning warning?)

(define-condition-type &serious &condition
  make-serious-condition serious-condition?)

(define-condition-type &error &serious
  make-error error?)

(define-condition-type &violation &serious
  make-violation violation?)

(define-condition-type &assertion &violation
  make-assertion-violation assertion-violation?)

(define-condition-type &irritants &condition
  make-irritants-condition irritants-condition?
  (irritants condition-irritants))

(define-condition-type &who &condition
  make-who-condition who-condition?
  (who condition-who))

(defn error 
  "Throw an exception that signals that an error has occurred.

  This function should be called when an error has occurred,
  typically caused by something that has gone wrong in the interaction
  of the program with the external world or the user."
  [who message & irritants]
  (if who
    (throw (combine-conditions (make-error)
                               (make-who-condition who)
                               (make-message-condition message)
                               (make-irritants-condition irritants)))
    (throw (combine-conditions (make-error)
                               (make-message-condition message)
                               (make-irritants-condition irritants)))))

(defn assertion-violation
  "Throw an exception that signals that an assertion was violated.

  THis should be called when an invalid call to a procedure was made,
  either passing an invalid number of arguments, or passing an argument
  that it is not specified to handle."
  [who message & irritants]
  (if who
    (throw (combine-conditions (make-assertion-violation)
                               (make-who-condition who)
                               (make-message-condition message)
                               (make-irritants-condition irritants)))
    (throw (combine-conditions (make-assertion-violation)
                               (make-message-condition message)
                               (make-irritants-condition irritants)))))

(defn stack-trace-who
  "Get a suitable argument for [[&who]] from an exception object."
  []
  (let [^Thread thr (Thread/currentThread)
        st (.getStackTrace thr)]
    ;; the top is the let, above that it's stack-trace-who itself
    (if-let [^StackTraceElement e (nth st 2)]
      (let [class (.getClassName e)
            method (.getMethodName e)]
        ;; adapted from clojure.stacktrace
        (let [match (re-matches #"^([A-Za-z0-9_.-]+)\$(\w+)$" (str class))]
          (if (and match (= "invoke" method))
            (apply format "%s/%s" (rest match))
            (format "%s.%s" class method)))))))

(defmacro assert
  "Evaluates expr and throws an exception if it does not evaluate to
  logical true."
  ([x]
     (when *assert*
       `(when-not ~x
          (assertion-violation (stack-trace-who)
                               (str "Assertion failed")
                               '~x))))
  ([x message]
     (when *assert*
       `(when-not ~x
          (assertion-violation (stack-trace-who)
                               (str "Assert failed: " ~message)
                               '~x)))))


(defmacro condition
  [?base ?message & ?irritants]
  `(combine-conditions ~?base
                       (make-who-condition (stack-trace-who))
                       (make-message-condition ~?message)
                       (make-irritants-condition [~@?irritants])))

(defmacro raise
  [?base ?message & ?irritants]
  `(throw (condition ~?base ~?message ~@?irritants)))

(defmacro guard
  [?handling & ?body]
  (when-not (vector? ?handling)
    (throw (IllegalArgumentException. (str "guard requires vector in " ~'*ns* ":" (:line (meta ~'&form))))))
  (when-not (odd? (count ?handling))
    (throw (IllegalArgumentException. (str "guard requires vector in " ~'*ns* ":" (:line (meta ~'&form))))))
  (let [?id (first ?handling)]
    `(try
       ~@?body
       (catch Throwable ~?id
         (cond
          ~@(rest ?handling)
          ~@(if (= :else (last (butlast ?handling)))
              `()
              `(:else (throw ~?id))))))))

(defn delete-first
  [pred? l]
  (letfn [(recurse [l]
            (cond
             (empty? l) l
             (pred? (first l)) (rest l)
             :else
             (cons (first l) (recurse (rest l)))))]
    (recurse l)))

(def ^:const ^:private covered-condition-types
  #{&error &assertion &serious})

(defn decode-condition
  "Return a keyword describing the type,
  a symbol or string describing the source of the problem, an error
  message or nil, and a sequence of other objects describing the
  problem.  

  Valid type symbols include: `:error`, `:assertion-violation`,
  `:violation`, `:serious`."
  [con]
  ;; adapted from Scheme 48
  (let [type (cond
              (error? con) :error
              (assertion-violation? con) :assertion-violation
              (violation? con) :violation
              (serious-condition? con) :serious
              :else 'unknown)

	who (and (who-condition? con)
                 (condition-who con))

	message (and (message-condition? con)
                     (condition-message con))

	stuff (if (irritants-condition? con)
                (condition-irritants con)
                [])

        more-stuff (delete-first
                    (fn [comp] ; make sure interesting subtypes still get printed
                      (contains? covered-condition-types
                                 (:type comp)))
                    ;; we don't expect interesting subtypes here
                    (delete-first
                     (partial condition-component-of-condition-type? &message)
                     (delete-first
                     (partial condition-component-of-condition-type? &who)
                      (delete-first
                       (partial condition-component-of-condition-type? &irritants)
                       (condition-components con)))))]

    [type who message (concat stuff more-stuff)]))

(defn- print-stack-trace-of
  [^Throwable exc]
  (let [st (.getStackTrace exc)]
    (if-let [e (first st)]
      (stack/print-trace-element e)
      (print "[empty stack trace]"))
    (newline)
    (doseq [e (rest st)]
      (print " ")
      (stack/print-trace-element e)
      (newline))))

(defn print-condition
  [c ^java.io.Writer w]
  (binding [*out* w]
    (let [[type who message stuff] (decode-condition c)]
      (doto w
        (.write (name type))
        (.write ": "))
      (if (string? message)
        (let [^String s message]
          (.write w s))
        (print message))
      (let [^String spaces
            (apply str (repeat (+ (count (name type)) 2)
                               \space))]
        (when who
          (.write w " [")
          (print who)
          (.write w "]"))
        (doseq [irritant stuff]
          (doto w
            (.write "\n")
            (.write spaces))
          (pr irritant))
        (.write w "\n")))
    (print-stack-trace-of c)))
