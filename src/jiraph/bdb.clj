(ns jiraph.bdb
  (:use [clojure.contrib java-utils])
  (:use jiraph.utils)
  (:import [com.sleepycat.je Environment EnvironmentConfig Transaction Database DatabaseConfig DatabaseEntry LockMode]
           [com.sleepycat.collections CurrentTransaction]))

(set! *warn-on-reflection* true)
(def *disable-transactions* false)

(defn- open-env [path]
  (let [config (doto (EnvironmentConfig.)
                 (.setAllowCreate true)
                 (.setTransactional (not *disable-transactions*))
                 (.setSharedCache true)
                 (.setLockTimeout (long (* 1000 500))))]
    (Environment. (file path) config)))

(defn- open-db [#^Environment env name]
  (let [config (doto (DatabaseConfig.)
                 (.setAllowCreate true)
                 (.setTransactional (not *disable-transactions*))
                 (.setDeferredWrite *disable-transactions*))]
    (.openDatabase env nil name config)))

(defn- current-txn [env]
  (let [txn #^CurrentTransaction (env :txn)]
    (if txn (.getTransaction txn))))

(defn db-open [path & args]
  (let [opts  (apply hash-map args)
        env   (open-env path)
        txn   (when-not *disable-transactions* (CurrentTransaction/getInstance env))]
    (-> opts
        (assoc-or :key-fn #(.getBytes (str %)))
        (assoc-or :val-fn #(.getBytes (str %)))        
        (assoc :env env)
        
        (assoc :nodes (open-db env "nodes"))
        (assoc :edges (open-db env "edges")))))

(defn db-sync [env]
  (let [env #^Environment (env :env)]
    (.sync env)))

(defn db-close [env]
  (let [nodes #^Database (env :nodes)
        edges #^Database (env :edges)
        env   #^Environment (env :env)]
    (.close nodes)
    (.close edges)
    (.close env)))

(defn db-set [env name key val]
  (let [key (DatabaseEntry. ((env :key-fn) key))
        val (DatabaseEntry. ((env :val-fn) val))
        txn #^Transaction (current-txn env)
        db  #^Database (env name)]
    (.put db txn key val))
  val)

(defn db-add [env name key val]
  (let [key (DatabaseEntry. ((env :key-fn) key))
        val (DatabaseEntry. ((env :val-fn) val))
        txn #^Transaction (current-txn env)
        db  #^Database (env name)]
    (.putNoOverwrite db txn key val))
  val)

(defn db-get [env name key]
  (let [key (DatabaseEntry. ((env :key-fn) key))
        val (DatabaseEntry.)
        txn #^Transaction (current-txn env)
        db  #^Database (env name)]
    (.get db txn key val LockMode/DEFAULT)
    (.getData val)))

(defmacro db-transaction [env & body]
  `(let [txn# #^CurrentTransaction (~env :txn)]
     (try
      (if txn# (.beginTransaction txn# nil))
      ~@body
      (if txn# (.commitTransaction txn#))
      (catch Exception e#
        (if txn# (.abortTransaction txn#))
        (throw e#)))))

(defn db-update [env name key fn]
  (db-transaction env
    (let [old #^bytes (db-get env name key)
          new (fn old)]
      (if new
        (db-set env name key new))
      new)))

(defn db-delete [env name key]
  (let [key (DatabaseEntry. ((env :key-fn) key))
        txn #^Transaction (current-txn env)
        db  #^Database (env name)]
    (.delete db txn key)))