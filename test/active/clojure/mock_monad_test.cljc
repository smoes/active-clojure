(ns active.clojure.mock-monad-test
  #?(:cljs (:require-macros [cljs.test :refer (is deftest run-tests testing)]))
  (:require [active.clojure.monad :as monad]
            [active.clojure.mock-monad :as mock]
            #?(:clj [clojure.test :refer :all])
            #?(:cljs [cljs.test])))

(defrecord Ask [prompt])
(defn ask [prompt] (Ask. prompt))
(defn ask? [x] (instance? Ask x))

(defrecord Tell [msg])
(defn tell [msg] (Tell. msg))
(defn tell? [x] (instance? Tell x))

(defn ex1
  []
  (monad/monadic
   [first (ask "what's your first name?")
    last (ask "what's your last name?")]
   (let [s (str "Hello, " first " " last)])
   (tell s)))

(deftest mock-run
  (let [result
        (mock/mock-run-monad
         [(mock/mock-result (ask "what's your first name?")
                            "first")
          (mock/mock-result (ask "what's your last name?")
                            "last")
          (mock/mock-result (tell "Hello, first last")
                            "I don't care, I am mocking you.")]
         (ex1))]
    (is (= "I don't care, I am mocking you." result))))