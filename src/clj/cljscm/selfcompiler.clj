;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljscm.selfcompiler
  (:refer-clojure :exclude [munge macroexpand-1])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [clojure.pprint :as pp]
            [cljscm.conditional :as condc]
            [cljscm.tagged-literals :as tags] 
            [cljscm.selfanalyzer :as ana])
  (:import java.lang.StringBuilder))
(set! *warn-on-reflection* true)
                                        ;Game plan : use emits, not print.
                                        ;unwrap nested emits.
                                        ; mapped emits - emits already nests into sequences.
                                        ; Q: For string things like space sep?? Looks like the way to do it is guard the emits as thunks that are sep'd by commas? Really is that the best way (only done when flipping to constant-emitting - otherwise we just leave the seq of maps be.) Or guard with-out-str if you're doing some string ops on the result.
                                        ; note that print spaces args, emits doesn't.
                                        ;don't use emit-wrap (no expression/statement dichotomy.)
(def js-reserved
  #{'begin 'letrec 'lambda 'vector 'make-vector 'vector-ref 'vector-set! 'length
    'make-array 'define 'table-set! 'table-ref 'make-table 'reverse
    'append-strings 'eq? 'raise})

(def ^:dynamic *position* nil)
(def ^:dynamic *emitted-provides* nil)
(def ^:dynamic *lexical-renames* {})
(def cljs-reserved-file-names #{"deps.cljs"})

(defn dispatch-munge [s]
  (-> s
      (clojure.string/replace "." "_")
      (clojure.string/replace "/" "$")))

(declare emits emitln)

(defn strict-str
  "recursively realizes any potentially lazy nested structure"
  ([] "")
  ([x] (str (walk/postwalk (fn [n] (cond (= clojure.lang.LazySeq (type n)) (apply list n)
                                         (identical? x ()) "()" ;o/w clojure.lang.PersistentList$EmptyList@1
                                         :else n)) x)))
  ([x & ys] (str (strict-str x) (apply strict-str ys))))

(defonce ns-first-segments (atom '#{"cljs" "clojure"}))

(defn munge
  ([s] (munge s js-reserved))
  ([s reserved]
     (if (map? s)
       (if (reserved (:name s)) (str (:name s) "$") (:name s))
      ; Unshadowing
      #_(let [{:keys [name field] :as info} s
            depth (loop [d 0, {:keys [shadow]} info]
                    (cond
                      shadow (recur (inc d) shadow)
                      (@ns-first-segments (str name)) (inc d)
                      :else d))
            renamed (*lexical-renames* (System/identityHashCode s))
            munged-name (munge (cond field (str "self__." name)
                                     renamed renamed
                                     :else name)
                               reserved)]
        #_(if (or field (zero? depth))
          munged-name
          (symbol (str munged-name "__$" depth))))
      ; String munging
      s
      #_(let [ss (string/replace (str s) #"\/(.)" ".$1") ; Division is special
            ss (apply str (map #(if (reserved %) (str % "$") %)
                               (string/split ss #"(?<=\.)|(?=\.)")))
            ms (clojure.lang.Compiler/munge ss)]
        (if (symbol? s)
          (symbol ms)
          ms)))))

(defn- comma-sep [xs]
  (interpose "," xs))
(defn- space-sep [xs]
  (interpose " " xs))

(defn- escape-char [^Character c]
  (let [cp (.hashCode c)]
    (case cp
      ; Handle printable escapes before ASCII
      34 "\\\""
      92 "\\\\"
      ; Handle non-printable escapes
      8 "\\b"
      12 "\\f"
      10 "\\n"
      13 "\\r"
      9 "\\t"
      (if (< 31 cp 127)
        c ; Print simple ASCII characters
        (format "\\u%04X" cp)))))

(defn- escape-string [^CharSequence s]
  (let [sb (StringBuilder. (count s))]
    (doseq [c s]
      (.append sb (escape-char c)))
    (.toString sb)))

(defn- wrap-in-double-quotes [x]
  (str \" x \"))

(defmulti emit :op)

(defn emit-begin
  [statements ret]
  (cons 'begin (map emit (concat statements [ret]))))

#_(defn emit-provide [sym]
  (when-not (or (nil? *emitted-provides*) (contains? @*emitted-provides* sym))
    (swap! *emitted-provides* conj sym)
    (emitln "goog.provide('" (munge sym) "');")))

(defmulti emit-constant class)

(defn- emit-meta-constant [src outform]
  (if (meta src)
    `(cljscm.core/with-meta ~outform ~(emit-constant (meta src)))
    outform))

(defmethod emit-constant :default [x] x)
(defmethod emit-constant clojure.lang.Symbol [x] (emit-meta-constant x `(quote ~x)))
(defmethod emit-constant java.util.regex.Pattern [x] :TODO-REGEX)


(defmethod emit-constant clojure.lang.PersistentList$EmptyList [x]
  `(~'list))

(defmethod emit-constant clojure.lang.PersistentList [x]
  (emit-meta-constant x `(cljscm.core/list ~@(map emit-constant x))))

(defmethod emit-constant clojure.lang.Cons [x]
  (emit-meta-constant x `(cljscm.core/list ~@(map emit-constant x))))

(defmethod emit-constant clojure.lang.IPersistentVector [x]
  (emit-meta-constant x
    `(cljscm.core/vec ~(cons 'list (map emit-constant x)))))

(defmethod emit-constant clojure.lang.IPersistentMap [x]
  (emit-meta-constant x
    `(cljscm.core/hash-map ~@(map emit-constant (apply concat x)))))

(defmethod emit-constant clojure.lang.PersistentHashSet [x]
  (emit-meta-constant x
    `(cljscm.core/set ~(cons 'list (map emit-constant x)))))
              
(defmacro emit-wrap [env & body]
  `(let [env# ~env]
     (when (= :return (:context env#)) (emits "return "))
     ~@body
     (when-not (= :expr (:context env#)) (emitln ";"))))

(defmethod emit :no-op [m])

(defmethod emit nil [m]
  (throw (Exception. (str "Analzyed form with null :op " (pr-str m)))))

(defmethod emit :var
  [{:keys [info env] :as arg}]
  (let [n (:name info)
        n (if (= (namespace n) "js")
            (name n)
            info)]
    (cond
      (:field info)
      , (emit (ana/analyze env
                           `(. ~(:this-name env)
                               ~(symbol (str "-" (munge n))))))
      (:dynamic info) (list (munge n))
      :else (munge n))))

(defmethod emit :meta [{:keys [expr meta]}]
  `(cljscm.core/with-meta ~(emit expr) ~(emit meta)))
(defmethod emit :map [{:keys [keys vals]}]
  (if (empty? keys)
    'cljscm.core/PersistentArrayMap-EMPTY
    `(cljscm.core/PersistentArrayMap-fromArrays ~(cons 'vector (map emit keys)) ~(cons 'vector (map emit vals)))))
(defmethod emit :vector [{:keys [items]}]
  (if (empty? items)
    'cljscm.core/PersistentVector-EMPTY
    `(cljscm.core/PersistentVector-fromArray ~(cons 'vector (map emit items)) true)))
(defmethod emit :set [{:keys [items]}]
  (if (empty? items)
    'cljscm.core/PersistentHashSet-EMPTY
    `(cljscm.core/PersistentHashSet-fromArray ~(cons 'vector (map emit items)))))
(defmethod emit :constant [{:keys [form]}] (emit-constant form))

(defn get-tag [e]
  (or (-> e :tag)
      (-> e :info :tag)
      (-> e :form meta :tag)))

(defn infer-tag [e]
  (if-let [tag (get-tag e)]
    tag
    (case (:op e)
      :let (infer-tag (:ret e))
      :if (let [then-tag (infer-tag (:then e))
                else-tag (infer-tag (:else e))]
            (when (= then-tag else-tag)
              then-tag))
      :constant (case (:form e)
                  true 'boolean
                  false 'boolean
                  nil)
      (get-tag e))))

(defn safe-test? [e]
  (let [tag (infer-tag e)]
    (#{'boolean} tag)))

(defmethod emit :if
  [{:keys [test then else unchecked]}]
  (let [checked (not (or unchecked (safe-test? test)))
        test (emit test)
        then (emit then)
        else (emit else)]
    (if checked
      (let [t (gensym "test")]
        `(let* ((~t ~test)) (if (~'and ~t (~'not (~'eq? nil ~t)))
                              ~then
                              ~else)))
       (list 'if test then else))))

(defmethod emit :case
  [{:keys [test clauses else]}]
  (concat `(~'case ~(emit test)
             ~@(for [[vals result] clauses]
                 `((~@(map emit vals)) ~(emit result))))
          (when else [(list 'else (emit else))])))

(defmethod emit :throw
  [{:keys [throw env]}] (list 'raise (emit throw)))

(defmethod emit :def
  [{:keys [name init dynamic env doc export]}]
  (let [i (when init [(emit init)])]
    (if dynamic
      (list 'define name (concat ['make-parameter] (or i [nil])))
      (concat ['define name] i))))

(defn schemify-method-arglist
  "analyzed method [a b & r] -> (a b . r) as a list.
   or [& r] -> r in the case of no fixed args.
   suitable for lambda-args, not define args.
   Munges any dontcares that are NOT in the first arg position. (can't
   munge that one, it might be a 'this' arg in a proto defn)"
  [{:keys [variadic max-fixed-arity params]}]
  (let [params (if (> (count params) 1)
                 (cons (first params)
                       (map #(if (= (:name %) '_) (assoc % :name (gensym "_")) %)
                            (rest params)))
                 params)]
    (if variadic
      (if (> max-fixed-arity 0)
        (map munge (concat (take max-fixed-arity params)
                           [(symbol "#&") (last params)]))
        (munge (first params)))
      (map munge params))))

(defn schemify-define-arglist
  "suitable for define-style forms, returns args without parens,
   and an inital dot for 0-fixed-arity variadics. Returns the string.
   Munges any dontcares that are NOT in the first arg position. (can't
   munge that one, it might be a 'this' arg in a proto defn)"
  [{:keys [variadic max-fixed-arity params]}]
  (let [params (if (> (count params) 1)
                 (cons (first params)
                       (map #(if (= (:name %) '_) (assoc % :name (gensym "_")) %)
                            (rest params)))
                 params)]
    (if variadic
      (map munge (concat (take max-fixed-arity params)
                         [(symbol "#&") (last params)]))
      (map munge params))))

;single-arity means we haven't done a "safe" arity dispatch.
(defn emit-fn-method
  [{:keys [gthis name variadic single-arity params expr env recurs max-fixed-arity toplevel] :as f}]
  (let [eexpr (rest (emit expr)) ;strip unecessary begin
        lamb (concat ['lambda (schemify-method-arglist f)]
                     (if (and variadic single-arity)
                       [`(let* ((~(munge (last params))
                                 (if (~'null? ~(munge (last params)))
                                   nil
                                   ~(munge (last params)))))
                               ~@eexpr)]
                       eexpr))]
    (if (and (or recurs name)
             (not toplevel)
             (not (:protocol-impl env))) ; preserve open recursion in inline proto defns.
      `(~'letrec ((~name ~lamb)) ~name)
      lamb)))

(defmethod emit :fn
  [{:keys [name env methods max-fixed-arity single-arity variadic recur-frames loop-lets toplevel]}]
  (when-not (= :statement (:context env))
    (let [loop-locals (->> (concat (mapcat :params (filter #(and % @(:flag %)) recur-frames))
                                   (mapcat :params loop-lets))
                           (map munge)
                           seq)
          recur-name (:recur-name env)]
      (if (= 1 (count methods))
        (emit-fn-method (assoc (first methods)
                          :toplevel toplevel
                          :name (or name (and (:recurs (first methods)) recur-name))
                          :single-arity single-arity))
        (throw (Exception. "Expected multiarity to be erased in macros."))))))

(defn- void-dontcare [s]
  (get {'_ "#!void"} s s))

(defmethod emit :extend
  [{:keys [etype impls base-type?]}]
  (cons
   'begin
   (apply
    concat
    (for [[protocol meth-map] impls]
      (cons `(~'table-set! (~'table-ref cljscm.core/protocol-impls ~(:name etype)) ~(:name protocol) true)
            (apply
             concat
             (for [[meth-name meth-impl] meth-map]
               (let [meth (first (:methods meth-impl))
                     rest? (:variadic meth)
                     impl-name (symbol (str (munge (:name (:info meth-name)))
                                            "---" (dispatch-munge (:name etype))))]
                 (when (> (count (:methods meth-impl)) 1) (throw (Exception. "should have compiled variadic defn away.")))
                 (concat
                  [(list 'define (cons impl-name (schemify-define-arglist meth))
                         (if rest?
                           (list 'apply (emit meth-impl)
                                 (list 'append
                                       (cons 'list (butlast (cons (munge (first (:params meth)))
                                                                  (map (comp void-dontcare munge)
                                                                       (rest (:params meth))))))
                                       (void-dontcare (munge (last (:params meth))))))
                           (cons (emit meth-impl) (cons (munge (first (:params meth)))
                                                        (map (comp void-dontcare munge)
                                                             (rest (:params meth)))))))]
                  (when-not base-type?
                    [(list 'table-set! (symbol (str (:name (:info meth-name)) "---vtable"))
                           (:name etype)
                           impl-name)]))))))))))

(defmethod emit :do
  [{:keys [statements ret env]}]
  (emit-begin statements ret))

(defmethod emit :try*
  [{:keys [env try catch name finally]}]
  (if (or name finally)
    (let [try-expr (if catch
                     `(let* ((cljscm.compiler/next-handler (~'current-exception-handler)))
                        (~'continuation-capture
                         (~'lambda (cljscm.compiler/unwinding-k)
                                 (~'with-exception-handler
                                   (~'lambda (~name)
                                             (~'with-exception-handler
                                               cljscm.compiler/next-handler
                                               (~'lambda () ~(emit catch))))
                                   (~'lambda () ~(emit try))))))
                     (emit try))
          rsym (gensym "ret")]
      (concat ['let* `((~rsym ~try-expr))]
              (when finally
                (assert (not= :constant (:op finally)) "finally block cannot contain constant")
                [(emit finally)])
              [rsym]))))

(defn emit-let
  [{:keys [bindings expr env recur-name]} kind]
  (binding [*lexical-renames* (into *lexical-renames*
                                    (when (= :statement (:context env))
                                      (map #(vector (System/identityHashCode %)
                                                    (gensym (str (:name %) "-")))
                                           bindings)))]
    (let [bs (for [{:keys [name init]} bindings] (list name (emit init)))]
      (case kind
        :loop (list 'let recur-name bs (emit expr))
        :let (list 'let* bs (emit expr))
        :letfn (list 'letrec bs (emit expr))))))

(defmethod emit :let [ast]
  (emit-let ast :let))

(defmethod emit :loop [ast]
  (emit-let ast :loop))

(defmethod emit :recur
  [{:keys [frame exprs env]}]
  (let [temps (vec (take (count exprs) (repeatedly gensym)))
        params (:params frame)
        recur-name (:recur-name env)]
    (cons  recur-name (map emit exprs))))

(defmethod emit :letfn
  [{:keys [bindings expr env] :as ast}]
  (emit-let ast :letfn))

(defn protocol-prefix [psym]
  (symbol (str (-> (str psym) (.replace \. \$) (.replace \/ \$)) "$")))

(defmethod emit :invoke ; TODO -- this is ignoring all new protocol stuff.
  [{:keys [f args env] :as expr}]
  (let [info (:info f)
        fn? (and ana/*cljs-static-fns*
                 (not (:dynamic info))
                 (:fn-var info))
        protocol (:protocol info)
        proto? (let [tag (infer-tag (first (:args expr)))]
                 (and protocol tag
                      (or ana/*cljs-static-fns*
                          (:protocol-inline env))
                      (or (= protocol tag)
                          (when-let [ps (:protocols (ana/resolve-existing-var (dissoc env :locals) tag))]
                            (ps protocol)))))
        opt-not? (and (= (:name info) 'cljscm.core/not)
                      (= (infer-tag (first (:args expr))) 'boolean))
        ns (:ns info)
        js? (= ns 'js)
        goog? (when ns
                (or (= ns 'goog)
                    (when-let [ns-str (str ns)]
                      (= (get (string/split ns-str #"\.") 0 nil) "goog"))))
        keyword? (and (= (-> f :op) :constant)
                      (keyword? (-> f :form)))
        [f variadic-invoke]
        (if fn?
          (let [arity (count args)
                variadic? (:variadic info)
                mps (:method-params info)
                mfa (:max-fixed-arity info)]
            (cond
             ;; if only one method, no renaming needed
             (and (not variadic?)
                  (= (count mps) 1))
             [f nil]

             ;; direct dispatch to variadic case
             (and variadic? (> arity mfa))
             [(update-in f [:info :name]
                             (fn [name] (symbol (str (munge name) ".cljs$lang$arity$variadic"))))
              {:max-fixed-arity mfa}]

             ;; direct dispatch to specific arity case
             :else
             (let [arities (map count mps)]
               (if (some #{arity} arities)
                 [(update-in f [:info :name]
                             (fn [name] (symbol (str (munge name) ".cljs$lang$arity$" arity)))) nil]
                 [f nil]))))
          [f nil])]
    ;;TODO leverage static information
    #_(emit-wrap env
      (cond
       opt-not?
       (emits "!(" (first args) ")")

       proto?
       (let [pimpl (str (munge (protocol-prefix protocol))
                        (munge (name (:name info))) "$arity$" (count args))]
         (emits (first args) "." pimpl "(" (comma-sep args) ")"))

       keyword?
       (emits "(new cljscm.core.Keyword(" f ")).call(" (comma-sep (cons "null" args)) ")")
       
       variadic-invoke
       (let [mfa (:max-fixed-arity variadic-invoke)]
        (emits f "(" (comma-sep (take mfa args))
               (when-not (zero? mfa) ",")
               "cljscm.core.array_seq([" (comma-sep (drop mfa args)) "], 0))"))
       
       (or fn? js? goog?)
       (emits f "(" (comma-sep args)  ")")
       
       :else
       (if (and ana/*cljs-static-fns* (= (:op f) :var))
         (let [fprop (str ".cljs$lang$arity$" (count args))]
           (emits "(" f fprop " ? " f fprop "(" (comma-sep args) ") : " f ".call(" (comma-sep (cons "null" args)) "))"))
         (emits f ".call(" (comma-sep (cons "null" args)) ")"))))
    (cons (emit f) (map emit args))))

(defmethod emit :new
  [{:keys [ctor args env]}]
  (cons (symbol (str "make-" (emit ctor))) (map emit args)))

(defmethod emit :set!
  [{:keys [target val env]}]
  (cond
    (and (= :var (:op target)) (:dynamic (:info target)))
    , (list (:name (:info target)) (emit val))
    (and (= :var (:op target)) (:field (:info target)))
    , (if-let [tag (get-tag (ana/resolve-existing-var env (:this-name env)))]
        (list (symbol (str (:name (ana/resolve-existing-var env tag))"-"(:name (:info target)) "-set!")) (:this-name env) (emit val))
        (list 'cljscm.core/record-set! (:this-name env) `(quote ~(:name (:info target)))  (emit val)))
      
    (= :dot (:op target))
    , (if-let [tag (get-tag (:target target))]
        (list (symbol (str (:name (ana/resolve-existing-var env tag))"-"(:field target)"-set!")) (emit (:target target)) (emit val))
        (list 'cljscm.core/record-set! (emit (:target target)) `(quote ~(:field target))  (emit val)))
    :else (list 'set! (emit target) (emit val))))

(defmethod emit :ns
  [{:keys [name requires uses requires-macros env]}]
  (swap! ns-first-segments conj (first (string/split (str name) #"\.")))
  (concat ['begin (list 'declare
                        (list 'standard-bindings)
                        (list 'extended-bindings)
                        (list 'block))]
          (when-not (= name 'cljscm.core)
            [(list 'load "cljscm.core")])
          (for [lib (into (vals requires) (distinct (vals uses)))]
            (list 'load (munge lib)))))

(defmethod emit :deftype*
  [{:keys [t fields no-constructor]}]
  (let [fields (map munge fields)]
    (list 'begin
          (list 'declare (list 'not 'safe))
          (concat ['define-type t] fields (when no-constructor  [:constructor false]))
          (list 'declare (list 'safe))
          (list 'define t (symbol (str "##type-" (count fields) "-"t)))
          (list 'table-set! 'cljscm.core/protocol-impls t (list 'make-table)))))


(comment (defmethod emit :defrecord*
  [{:keys [t fields pmasks]}]
  (let [fields (concat (map munge fields) '[__meta __extmap])]
    (emit-provide t)
    (emitln "")
    (emitln "/**")
    (emitln "* @constructor")
    (doseq [fld fields]
      (emitln "* @param {*} " fld))
    (emitln "* @param {*=} __meta ")
    (emitln "* @param {*=} __extmap")
    (emitln "*/")
    (emitln (munge t) " = (function (" (comma-sep fields) "){")
    (doseq [fld fields]
      (emitln "this." fld " = " fld ";"))
    (doseq [[pno pmask] pmasks]
      (emitln "this.cljs$lang$protocol_mask$partition" pno "$ = " pmask ";"))
    (emitln "if(arguments.length>" (- (count fields) 2) "){")
    (emitln "this.__meta = __meta;")
    (emitln "this.__extmap = __extmap;")
    (emitln "} else {")
    (emits "this.__meta=")
    (emit-constant nil)
    (emitln ";")
    (emits "this.__extmap=")
    (emit-constant nil)
    (emitln ";")
    (emitln "}")
    (emitln "})"))))

(defmethod emit :dot
  [{:keys [target field method args env]}]
  (if field
    (if-let [tag (get-tag target)]
      (list (symbol (str (:name (ana/resolve-existing-var env tag)) "-" field)) (emit target))
      (list 'cljscm.core/record-ref (emit target) `(quote ~field)))
    (throw (Exception. (str "no special dot-method access: " (:line env) " target:" (:op target)))))) ;TODO

(defmethod emit :scm-str
  [{:keys [env code segs args]}]
  (throw (Exception. (str "scm-str* disabled in selfcompiler" (:line env))))
  #_(if code
    (emit code)
    (apply concat (interleave (concat segs (repeat nil))
                              (concat args [nil])))))

;form->form mapping (or a vector of candidate forms) that will be subject to analyze->emit in context.
(defmethod emit :scm
  [{:keys [env symbol-map form]}]
  (let [symbol-map (if (and (coll? symbol-map) (not (map? symbol-map)))
                     (into {} (map vector symbol-map symbol-map))
                     symbol-map)
        subbed-form (walk/prewalk
                     (fn [t] (let [r (get symbol-map t ::not-found)]
                               (if (= ::not-found r)
                                 t
                                 (emit (ana/analyze (assoc env :context :return) r))))) form)]
    (assert (= 1 (count subbed-form)) "only one form in scm*")
    (first subbed-form)))

(defn forms-seq
  "Seq of forms in a Clojure or ClojureScript file."
  ([f]
     (forms-seq f (clojure.lang.LineNumberingPushbackReader. (io/reader f))))
  ([f ^java.io.PushbackReader rdr]
     (if-let [form (binding [*ns* ana/*reader-ns*] (read rdr nil nil))]
       (lazy-seq (cons form (forms-seq f rdr)))
       (.close rdr))))

(defn rename-to-scm
  "Change the file extension from .cljscm to .js. Takes a File or a
  String. Always returns a String."
  [file-str]
  (clojure.string/replace file-str #"\.clj.*$" ".scm"))

(defn mkdirs
  "Create all parent directories for the passed file."
  [^java.io.File f]
  (.mkdirs (.getParentFile (.getCanonicalFile f))))

(defmacro with-core-cljs
  "Ensure that core.cljscm has been loaded."
  [& body]
  `(do (when-not (:defs (get @ana/namespaces 'cljscm.core))
         (ana/analyze-file "cljscm/core.cljscm"))
       ~@body))

(defn compile-file* [src dest]
  (with-core-cljs
    (with-open [out ^java.io.Writer (io/make-writer dest {})]
      (binding [*out* out
                ana/*cljs-ns* 'cljscm.user
                ana/*cljs-file* (.getPath ^java.io.File src)
                *data-readers* tags/*cljs-data-readers*
                *position* (atom [0 0])
                *emitted-provides* (atom #{})
                condc/*target-platform* :gambit]
        (loop [forms (forms-seq src)
               ns-name nil
               deps nil]
          (if (seq forms)
            (let [env (ana/empty-env)
                  ast (ana/analyze env (first forms))]
              (do (prn (emit ast))
                  #_(pp/pprint (emit ast))
                  (if (= (:op ast) :ns)
                    (recur (rest forms) (:name ast) (merge (:uses ast) (:requires ast)))
                    (recur (rest forms) ns-name deps))))
            {:ns (or ns-name 'cljscm.user)
             :provides [ns-name]
             :requires (if (= ns-name 'cljscm.core) (set (vals deps)) (conj (set (vals deps)) 'cljscm.core))
             :file dest}))))))

(defn requires-compilation?
  "Return true if the src file requires compilation."
  [^java.io.File src ^java.io.File dest]
  (or (not (.exists dest))
      (> (.lastModified src) (.lastModified dest))))

(defn parse-ns [src dest]
  (with-core-cljs
    (binding [ana/*cljs-ns* 'cljscm.user]
      (loop [forms (forms-seq src)]
        (if (seq forms)
          (let [env (ana/empty-env)
                ast (ana/analyze env (first forms))]
            (if (= (:op ast) :ns)
              (let [ns-name (:name ast)
                    deps    (merge (:uses ast) (:requires ast))]
                {:ns (or ns-name 'cljscm.user)
                 :provides [ns-name]
                 :requires (if (= ns-name 'cljscm.core)
                             (set (vals deps))
                             (conj (set (vals deps)) 'cljscm.core))
                 :file dest})
              (recur (rest forms)))))))))

(defn compile-file
  "Compiles src to a file of the same name, but with a .js extension,
   in the src file's directory.

   With dest argument, write file to provided location. If the dest
   argument is a file outside the source tree, missing parent
   directories will be created. The src file will only be compiled if
   the dest file has an older modification time.

   Both src and dest may be either a String or a File.

   Returns a map containing {:ns .. :provides .. :requires .. :file ..}.
   If the file was not compiled returns only {:file ...}"
  ([src]
     (let [dest (rename-to-scm src)]
       (compile-file src dest)))
  ([src dest]
     (let [src-file (io/file src)
           dest-file (io/file dest)]
       (if (.exists src-file)
         (if (requires-compilation? src-file dest-file)
           (do (mkdirs dest-file)
               (compile-file* src-file dest-file))
           (parse-ns src-file dest-file))
         (throw (java.io.FileNotFoundException. (str "The file " src " does not exist.")))))))

(comment
  ;; flex compile-file
  (do
    (compile-file "/tmp/hello.cljs" "/tmp/something.js")
    (slurp "/tmp/hello.js")

    (compile-file "/tmp/somescript.cljs")
    (slurp "/tmp/somescript.js")))

(defn path-seq
  [file-str]
  (->> java.io.File/separator
       java.util.regex.Pattern/quote
       re-pattern
       (string/split file-str)))

(defn to-path
  ([parts]
     (to-path parts java.io.File/separator))
  ([parts sep]
     (apply strict-str (interpose sep parts))))

(defn to-target-file
  "Given the source root directory, the output target directory and
  file under the source root, produce the target file."
  [^java.io.File dir ^String target ^java.io.File file]
  (let [dir-path (path-seq (.getAbsolutePath dir))
        file-path (path-seq (.getAbsolutePath file))
        relative-path (drop (count dir-path) file-path)
        parents (butlast relative-path)
        parent-file (java.io.File. ^String (to-path (cons target parents)))]
    (java.io.File. parent-file ^String (rename-to-scm (last relative-path)))))

(defn cljs-files-in
  "Return a sequence of all .cljscm files in the given directory."
  [dir]
  (filter #(let [name (.getName ^java.io.File %)]
             (and (.endsWith name ".cljscm")
                  (not= \. (first name))
                  (not (contains? cljs-reserved-file-names name))))
          (file-seq dir)))

(defn compile-root
  "Looks recursively in src-dir for .cljs files and compiles them to
   .js files. If target-dir is provided, output will go into this
   directory mirroring the source directory structure. Returns a list
   of maps containing information about each file which was compiled
   in dependency order."
  ([src-dir]
     (compile-root src-dir "out"))
  ([src-dir target-dir]
     (let [src-dir-file (io/file src-dir)]
       (loop [cljs-files (cljs-files-in src-dir-file)
              output-files []]
         (if (seq cljs-files)
           (let [cljs-file (first cljs-files)
                 output-file ^java.io.File (to-target-file src-dir-file target-dir cljs-file)
                 ns-info (compile-file cljs-file output-file)]
             (recur (rest cljs-files) (conj output-files (assoc ns-info :file-name (.getPath output-file)))))
           output-files)))))

(comment
  ;; compile-root
  ;; If you have a standard project layout with all file in src
  (compile-root "src")
  ;; will produce a mirrored directory structure under "out" but all
  ;; files will be compiled to js.
  )

(comment

;;the new way - use the REPL!!
(require '[cljscm.compiler :as comp])
(def repl-env (comp/repl-env))
(comp/repl repl-env)
;having problems?, try verbose mode
(comp/repl repl-env :verbose true)
;don't forget to check for uses of undeclared vars
(comp/repl repl-env :warn-on-undeclared true)

(test-stuff)
(+ 1 2 3)
([ 1 2 3 4] 2)
({:a 1 :b 2} :a)
({1 1 2 2} 1)
(#{1 2 3} 2)
(:b {:a 1 :b 2})
('b '{:a 1 b 2})

(extend-type number ISeq (-seq [x] x))
(seq 42)
;(aset cljscm.core.ISeq "number" true)
;(aget cljscm.core.ISeq "number")
(satisfies? ISeq 42)
(extend-type nil ISeq (-seq [x] x))
(satisfies? ISeq nil)
(seq nil)

(extend-type default ISeq (-seq [x] x))
(satisfies? ISeq true)
(seq true)

(test-stuff)

(array-seq [])
(defn f [& etc] etc)
(f)

(in-ns 'cljscm.core)
;;hack on core


(deftype Foo [a] IMeta (-meta [_] (fn [] a)))
((-meta (Foo. 42)))

;;OLD way, don't you want to use the REPL?
(in-ns 'cljscm.compiler)
(import '[javax.script ScriptEngineManager])
(def jse (-> (ScriptEngineManager.) (.getEngineByName "JavaScript")))
(.eval jse cljscm.compiler/bootjs)
(def envx {:ns (@namespaces 'cljscm.user) :context :expr :locals '{ethel {:name ethel__123 :init nil}}})
(analyze envx nil)
(analyze envx 42)
(analyze envx "foo")
(analyze envx 'fred)
(analyze envx 'fred.x)
(analyze envx 'ethel)
(analyze envx 'ethel.x)
(analyze envx 'my.ns/fred)
(analyze envx 'your.ns.fred)
(analyze envx '(if test then else))
(analyze envx '(if test then))
(analyze envx '(and fred ethel))
(analyze (assoc envx :context :statement) '(def test "fortytwo" 42))
(analyze (assoc envx :context :expr) '(fn* ^{::fields [a b c]} [x y] a y x))
(analyze (assoc envx :context :statement) '(let* [a 1 b 2] a))
(analyze (assoc envx :context :statement) '(defprotocol P (bar [a]) (baz [b c])))
(analyze (assoc envx :context :statement) '(. x y))
(analyze envx '(fn foo [x] (let [x 42] (js* "~{x}['foobar']"))))

(analyze envx '(ns fred (:require [your.ns :as yn]) (:require-macros [clojure.core :as core])))
(defmacro js [form]
  `(emit (ana/analyze {:ns (@ana/namespaces 'cljscm.user) :context :statement :locals {}} '~form)))

(defn jscapture [form]
  "just grabs the js, doesn't print it"
  (with-out-str
    (emit (analyze {:ns (@namespaces 'cljscm.user) :context :expr :locals {}} form))))

(defn jseval [form]
  (let [js (jscapture form)]
    ;;(prn js)
    (.eval jse (str "print(" js ")"))))

;; from closure.clj
(optimize (jscapture '(defn foo [x y] (if true 46 (recur 1 x)))))

(js (if a b c))
(js (def x 42))
(js (defn foo [a b] a))
(js (do 1 2 3))
(js (let [a 1 b 2 a b] a))

(js (ns fred (:require [your.ns :as yn]) (:require-macros [cljscm.core :as core])))

(js (def foo? (fn* ^{::fields [a? b c]} [x y] (if true a? (recur 1 x)))))
(js (def foo (fn* ^{::fields [a b c]} [x y] (if true a (recur 1 x)))))
(js (defn foo [x y] (if true x y)))
(jseval '(defn foo [x y] (if true x y)))
(js (defn foo [x y] (if true 46 (recur 1 x))))
(jseval '(defn foo [x y] (if true 46 (recur 1 x))))
(jseval '(foo 1 2))
(js (and fred ethel))
(jseval '(ns fred (:require [your.ns :as yn]) (:require-macros [cljscm.core :as core])))
(js (def x 42))
(jseval '(def x 42))
(jseval 'x)
(jseval '(if 42 1 2))
(jseval '(or 1 2))
(jseval '(fn* [x y] (if true 46 (recur 1 x))))
(.eval jse "print(test)")
(.eval jse "print(cljscm.user.Foo)")
(.eval jse  "print(cljscm.user.Foo = function (){\n}\n)")
(js (def fred 42))
(js (deftype* Foo [a b-foo c]))
(jseval '(deftype* Foo [a b-foo c]))
(jseval '(. (new Foo 1 2 3) b-foo))
(js (. (new Foo 1 2 3) b))
(.eval jse "print(new cljscm.user.Foo(1, 42, 3).b)")
(.eval jse "(function (x, ys){return Array.prototype.slice.call(arguments, 1);})(1,2)[0]")

(macroexpand-1 '(cljscm.core/deftype Foo [a b c] Fred (fred [x] a) (fred [x y] b) (ethel [x] c) Ethel (foo [] d)))
(-> (macroexpand-1 '(cljscm.core/deftype Foo [a b c] Fred (fred [x] a) (fred [x y] b) (ethel [x] c) Ethel (foo [] d)))
    last last last first meta)

(macroexpand-1 '(cljscm.core/extend-type Foo Fred (fred ([x] a) ([x y] b)) (ethel ([x] c)) Ethel (foo ([] d))))
(js (new foo.Bar 65))
(js (defprotocol P (bar [a]) (baz [b c])))
(js (. x y))
(js (. "fred" (y)))
(js (. x y 42 43))
(js (.. a b c d))
(js (. x (y 42 43)))
(js (fn [x] x))
(js (fn ([t] t) ([x y] y) ([ a b & zs] b)))

(js (. (fn foo ([t] t) ([x y] y) ([a b & zs] b)) call nil 1 2))
(js (fn foo
      ([t] t)
      ([x y] y)
      ([ a b & zs] b)))

(js ((fn foo
       ([t] (foo t nil))
       ([x y] y)
       ([ a b & zs] b)) 1 2 3))


(jseval '((fn foo ([t] t) ([x y] y) ([ a b & zs] zs)) 12 13 14 15))

(js (defn foo [this] this))

(js (defn foo [a b c & ys] ys))
(js ((fn [x & ys] ys) 1 2 3 4))
(jseval '((fn [x & ys] ys) 1 2 3 4))
(js (cljscm.core/deftype Foo [a b c] Fred (fred [x] a) (fred [x y] a)  (ethel [x] c) Ethel (foo [] d)))
(jseval '(cljscm.core/deftype Foo [a b c] Fred (fred [x] a) (fred [x y] a)  (ethel [x] c) Ethel (foo [] d)))

(js (do
           (defprotocol Proto (foo [this]))
           (deftype Type [a] Proto (foo [this] a))
           (foo (new Type 42))))

(jseval '(do
           (defprotocol P-roto (foo? [this]))
           (deftype T-ype [a] P-roto (foo? [this] a))
           (foo? (new T-ype 42))))

(js (def x (fn foo [x] (let [x 42] (js* "~{x}['foobar']")))))
(js (let [a 1 b 2 a b] a))

(doseq [e '[nil true false 42 "fred" fred ethel my.ns/fred your.ns.fred
            (if test then "fooelse")
            (def x 45)
            (do x y y)
            (fn* [x y] x y x)
            (fn* [x y] (if true 46 (recur 1 x)))
            (let* [a 1 b 2 a a] a b)
            (do "do1")
            (loop* [x 1 y 2] (if true 42 (do (recur 43 44))))
            (my.foo 1 2 3)
            (let* [a 1 b 2 c 3] (set! y.s.d b) (new fred.Ethel a b c))
            (let [x (do 1 2 3)] x)
            ]]
  (->> e (analyze envx) emit)
  (newline)))
