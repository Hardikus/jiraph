(ns jiraph.masai-layer
  (:use [jiraph.layer :as layer
         :only [Enumerate Optimized Historical Basic Layer ChangeLog Schema
                node-id-seq]]
        [jiraph.formats :only [special-codec]]
        [jiraph.utils :only [meta-id meta-id? base-id id->str meta-str?]]
        [jiraph.codex :only [encode decode]]
        [jiraph.masai-common :only [implement-ordered revision-to-read]]
        [retro.core :only [Transactional Revisioned OrderedRevisions
                           at-revision txn-begin! txn-commit! txn-rollback!]]
        [useful.utils :only [if-ns adjoin returning map-entry]]
        [useful.map :only [update-in*]]
        [useful.seq :only [find-with]]
        [useful.state :only [volatile put!]]
        [useful.fn :only [as-fn fix given]]
        [useful.datatypes :only [assoc-record]]
        [io.core :only [bufseq->bytes]])
  (:require [masai.db :as db]
            [jiraph.graph :as graph :refer [with-action]]
            [jiraph.formats.cereal :as cereal])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStreamReader
            DataOutputStream DataInputStream]
           [java.nio ByteBuffer]))

;;; A masai-layer "format" is a map containing, at least, a Gloss codec for encoding nodes,
;;; under the :codec key. Optional components:
;;; - a Schematic :schema
;;; - the :reduce-fn the codec uses when combining revisions
;;;   - this is used to optimize updates
;;; - a :reset codec, for writing data that should not use the reduce-fn
;;;   - this codec need not be capable of reading - it will only be written with
;;; - a :revisions codec, for reading the list of revisions at which a node has been touched.

(defn write-format [layer node-id]
  ((:format-fn layer) {:id node-id :revision (:revision layer)}))

(defn read-format [layer node-id]
  ((:format-fn layer) {:id node-id :revision (revision-to-read layer)}))

(defn- revision-seq [format revision bytes]
  (when-let [rev-codec (:revisions format)]
    (let [revs (decode rev-codec bytes)]
      (distinct
       (if-not revision
         revs
         (take-while #(<= % revision) revs)))))  )

(defn- overwrite [layer id attrs]
  (let [{:keys [db append-only?]} layer]
    (letfn [(bytes [data]
              (let [format (write-format layer id)
                    codec (or (and append-only?
                                   (:reset format))
                              (:codec format))]
                (encode codec data)))]
      ((if append-only? db/append! db/put!)
       db (id->str id) (bytes attrs)))))

(defn- assert-length [len coll]
  (if (zero? len)
    (assert (empty? coll) "Too many elements")
    (let [last-expected (nthnext coll (dec len))]
      (assert last-expected "Too few elements")
      (assert (not (next last-expected)) "Too many elements")))
  coll)

(defrecord MasaiLayer [db revision max-written-revision append-only? format-fn]
  Object
  (toString [this]
    (pr-str this))

  Enumerate
  (node-id-seq [this]
    (remove meta-str? (db/key-seq db)))
  (node-seq [this]
    (for [id (node-id-seq this)]
      (map-entry id (graph/get-node this id))))

  Basic
  (get-node [this id not-found]
    (if-let [data (db/fetch db (id->str id))]
      (decode (:codec (read-format this id))
              data)
      not-found))
  (update-in-node [this keyseq f args]
    (letfn [(ioval [write]
              (fn [read]
                [{:write (write read) :wrap-read (graph/read-wrapper this keyseq f args)
                  :layer this :keyseq keyseq :f f :args args}]))]
      (if-let [[id & keys] (seq keyseq)]
        (ioval (if (= f (:reduce-fn (write-format this id)))
                 (let [[attrs] (assert-length 1 args)]
                   (constantly (fn [layer]
                                 (->> (if keys
                                        (assoc-in {} keys attrs)
                                        attrs)
                                      (encode (:codec (write-format layer id)))
                                      (db/append! db (id->str id))))))
                 (fn write [read]
                   (fn [layer]
                     (let [old (read layer [id])
                           new (apply update-in* old keys f args)]
                       (overwrite layer id new))))))
        (condp = f
          assoc (let [[id attrs] (assert-length 2 args)]
                  (ioval (constantly (fn [layer]
                                       (overwrite layer id attrs)))))
          dissoc (let [[id] (assert-length 1 args)]
                   (ioval (constantly (fn [layer]
                                        (db/delete! db (id->str))))))
          (throw (IllegalArgumentException. (format "Can't apply function %s at top level"
                                                    f)))))))

  Optimized
  (query-fn [this keyseq not-found f] nil)

  Layer
  (open [this]
    (db/open db))
  (close [this]
    (db/close db))
  (sync! [this]
    (db/sync! db))
  (optimize! [this]
    (db/optimize! db))
  (truncate! [this]
    (db/truncate! db)
    (put! max-written-revision nil))
  (same? [this other]
    (apply = (for [layer [this other]]
               (get-in layer [:db :opts :path]))))

  Schema
  (schema [this id]
    (:schema (read-format this id)))
  (verify-node [this id attrs]
    (try
      ;; do a fake write (does no I/O), to see if an exception would occur
      (encode (:codec (write-format this id))
              attrs)
      (catch Exception _ false)))

  ChangeLog
  (get-revisions [this id]
    (when-let [data (db/fetch db (id->str id))]
      (revision-seq (read-format (at-revision this nil) id) revision data)))

  Historical
  (node-history [this id]
    (when-let [data (db/fetch db (id->str id))]
      (if-let [historical-codec (:historical (read-format this id))]
        (decode historical-codec data)
        (when-let [revisions (revision-seq (read-format (at-revision this nil)
                                                        id)
                                           revision data)]
          (into (sorted-map)
                (for [revision revisions]
                  [revision (decode (:codec
                                     (read-format (at-revision this revision)
                                                  id))
                                    data)]))))))

  ;; TODO this is stubbed, will need to work eventually
  (get-changed-ids [this rev]
    #{})

  Transactional
  (txn-begin! [this]
    (txn-begin! db))
  (txn-commit! [this]
    (txn-commit! db))
  (txn-rollback! [this]
    (put! max-written-revision nil)
    (txn-rollback! db))

  Revisioned
  (at-revision [this rev]
    (assoc-record this :revision rev))
  (current-revision [this]
    revision))

(implement-ordered MasaiLayer)

(if-ns (:require [masai.tokyo :as tokyo])
       (defn- make-db [db]
         (if (string? db)
           (tokyo/make {:path db :create true})
           db))
       (defn- make-db [db]
         db))

(let [default-format-fn (cereal/revisioned-clojure-format adjoin)]
  ;; format-fn should be a function:
  ;; - accept as arg: a map containing {revision and node-id}
  ;; - return: a format (see doc for formats at the top of this file)
  (defn make [db & {:keys [assoc-mode format-fn] :or {assoc-mode :append}}]
    (MasaiLayer. (make-db db) nil (volatile nil)
                 (case assoc-mode
                   :append true
                   :overwrite false)
                 (as-fn (or format-fn default-format-fn)))))

(defn temp-layer
  "Create a masai layer on a temporary file, deleting the file when the JVM exits.
   Returns a pair of [file layer]."
  [& args]
  (let [file (java.io.File/createTempFile "layer" "db")
        name (.getAbsolutePath file)]
    (returning [file (apply make name args)]
      (.deleteOnExit file))))

(def make-temp (comp second temp-layer))

(defmacro with-temp-layer [[binding & args] & body]
  `(let [[file# layer#] (temp-layer ~@args)
         ~binding layer#]
     (layer/open layer#)
     (returning ~@body
       (layer/close layer#)
       (.delete file#))))
