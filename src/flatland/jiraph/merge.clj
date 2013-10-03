(ns flatland.jiraph.merge
  (:refer-clojure :exclude [merge])
  (:require [clojure.core :as clojure]
            [flatland.jiraph.graph :refer [compose update-in-node get-in-node assoc-node]]
            [flatland.jiraph.ruminate :as ruminate]
            [flatland.jiraph.layer :as layer :refer [child]]
            [flatland.retro.core :refer [at-revision]]
            [flatland.useful.map :refer [update map-vals filter-vals]]
            [flatland.useful.utils :refer [adjoin invoke verify]]))

(defn M
  [head tail]
  (letfn [(remove-deleted-edges [node]
            (update node :edges filter-vals :exists))]
    (apply adjoin (map remove-deleted-edges [tail head]))))

(letfn [(throw-up! []
          (throw (Exception. (str "You're not supposed to call this function; update-in-node on the"
                                  " merge layer treats it specially and doesn't call it."))))]
  (defn merge [head tail-id phantom-id]
    (throw-up!))

  (defn unmerge [head tail-id]
    (throw-up!)))

(defn merge-head [read merge-layer node-id]
  ;; ...
  )

(defn- verify-merge-args! [keyseq f args]
  (verify (and (#{merge unmerge} f)
               (= 1 (count keyseq))
               (= (count args) (if (= f merge) 2 1)))
          "Merge layer only supports functions merge and unmerge, only at the top level."))

(defn compose-with [read & iovals]
  ((apply compose iovals) read))

(defn existing-edges [read layer id]
  (seq (->> (read layer [id :edges])
            (filter (comp :exists val)))))

(defn root-edge-finder [read merge-layer]
  (memoize
   (fn [id]
     (when-let [merge-edges (existing-edges read merge-layer id)]
       (verify (not (next merge-edges))
               (format "Can't read %s, which appears to be a phantom, as it has edges to %s"
                       (pr-str id) (pr-str (keys merge-edges))))
       (when-first [edge merge-edges]
         (verify (:position (val edge))
                 (format "No position found on edge from %s to root - maybe not a leaf?"
                         (pr-str (key edge))))
         edge)))))

(defn leaf-finder [read merge-layer]
  (let [incoming (child merge-layer :incoming)]
    (memoize
     (fn [root-id]
       (read incoming [root-id :edges])))))

(defn parent-finder [read merge-layer]
  (let [incoming (child merge-layer :incoming)]
    (memoize
     (fn [id]
       (when-let [edges (existing-edges read incoming id)]
         (verify (not (next edges))
                 (format "Node %s has multiple incoming edges %s - maybe a root?"
                         (pr-str id) (pr-str (keys edges))))
         (key (first edges)))))))

(defn child-finder [read merge-layer]
  (memoize
   (fn [id]
     (->> (existing-edges read merge-layer id)
          (remove (comp :position val))
          (keys)))))

(defn leaf-seq [children id]
  (if-let [cs (seq (children id))]
    (mapcat (partial leaf-seq children) id)
    [id]))

(defn head-finder [read merge-layer]
  (memoize
   (fn [root-id]
     (read merge-layer [root-id :head]))))

(defn- ruminate-merge-node [merge-layer layers keyseq f args]
  (fn [read]
    (let [[head-id] keyseq
          [tail-id] args]
      (compose-with read
        (apply update-in-node merge-layer keyseq f args)
        (for [layer layers]
          (condp = f
            merge (fn [read]
                    ;; write (M head tail) to the head, and delete the tail
                    (let [head (read layer [head-id])
                          tail (read layer [tail-id])]
                      (compose-with read
                        (update-in-node layer [] assoc head-id (M head tail))
                        (update-in-node layer [] dissoc tail-id))))
            unmerge '(fn [read]
                      (let [merge-rev '...
                            before-merge (at-revision layer (dec merge-rev))
                            [head tail] (for [id [head-id tail-id]]
                                          (get-in-node before-merge [id]))]
                        (compose-with read
                          (assoc-node layer tail-id (E* read tail))
                          ;; walk
                          )))))))))

(defn ruminate-merge-edges [merge-layer layers keyseq f args]
  (fn [read]
    (let [[head-id] keyseq
          [tail-id] args]
      (compose-with read
        (apply update-in-node merge-layer keyseq f args)
        (for [layer layers]
          (condp = f
            merge (fn [read]
                    ;; use incoming layer to find all edges to the tail, and point them at
                    ;; the head instead
                    (when-let [incoming (child layer :incoming)]
                      (compose-with read
                        (for [[from-id edge] (read incoming [tail-id :edges])
                              :when (:exists edge)]
                          (let [new-edge (apply adjoin ;; make sure head wins the adjoin
                                                (for [to-id [tail-id head-id]]
                                                  (read layer [from-id :edges to-id])))]
                            (update-in-node layer [from-id :edges] adjoin
                                            {tail-id {:exists false}
                                             head-id new-edge}))))))
            unmerge (fn [read]
                      '...
                      )))))))

(defn- leaf-updater [layer get-head get-root get-leaves]
  (fn [id root-id offset]
    (if-let [[old-root] (get-root id)]
      (do (verify (= id (get-head old-root))
                  (format "%s is already a tail: cannot merge it with another node"
                          (pr-str id)))
          (->> (get-leaves old-root)
               (sort-by (comp val :position))
               (map-indexed (fn [i [leaf-id]]
                              [(update-in-node layer [leaf-id :edges old-root]
                                               adjoin {:exists false})
                               (update-in-node layer [leaf-id :edges root-id]
                                               adjoin {:exists true, :posiion (+ i offset)})]))))
      [(update-in-node layer [id :edges root-id]
                       adjoin {:exists true, :position offset})])))

(defn- child-adder [layer get-root]
  (fn [root-id head-id tail-id]
    (update-in-node layer [root-id]
                    adjoin {:head head-id
                            :edges (into {}
                                         (for [id [head-id tail-id]]
                                           [(if-let [[root] (get-root id)]
                                              root, id)
                                            {:exists true}]))})))

(defn ruminate-merge [layer [] keyseq f args]
  (verify-merge-args! keyseq f args)
  (fn [read]
    (let [mread (memoize read)
          [head-id] keyseq
          [tail-id root-id] args
          get-head (head-finder mread layer)
          get-root (root-edge-finder mread layer)
          get-leaves (leaf-finder mread layer)]
      (condp = f
        merge (if (seq (read layer [root-id]))
                (throw (IllegalStateException.
                        (format "Can't use %s as root of new merge, as it already exists"
                                (pr-str root-id))))
                (let [update-leaves (leaf-updater layer get-head get-root get-leaves)
                      add-children (child-adder layer get-root)
                      head-leaf-updates (update-leaves head-id root-id 0)
                      tail-leaf-updates (update-leaves tail-id root-id (count head-leaf-updates))]
                  (compose-with read
                    head-leaf-updates,
                    tail-leaf-updates,
                    (add-children root-id head-id tail-id))))
        unmerge (let [get-parent (parent-finder mread layer)
                      get-children (child-finder mread layer)
                      [root] (get-root tail-id)]
                  (verify root
                          (format "Can't unmerge %s from %s, as it is not merged into anything"
                                  (pr-str tail-id) (pr-str head-id)))
                  (verify (= head-id (get-head root))
                          (format "Can't unmerge %s from %s, as its head is actually %s"
                                  (pr-str tail-id) (pr-str head-id) (pr-str (get-head root))))
                  (let [parents (iterate get-parent tail-id)
                        merge-child (last (cons tail-id
                                                (take-while #(= tail-id (get-head %))
                                                            parents)))
                        merge-parent (get-parent merge-child)]
                    (compose-with read
                      (update-in-node layer [merge-parent :edges merge-child]
                                      adjoin {:exists false})
                      (for [leaf-id (leaf-seq get-children tail-id)]
                        (update-in-node layer [leaf-id :edges] adjoin
                                        {root {:exists false}
                                         merge-child (val (get-root leaf-id))})))))))))

;; - what revision tail was merged into head
;; - get versions of head/tail just prior to merge
;;

;; what about:
;; - two layers of merging ruminants:
;;   - one that doesn't merge edge destination ids at all, just merging node data (including edges)
;;   - one that ruminates on the above, and does just edge-destination merging
;; - can unmerge by looking at a historical view of the "less-merged" layer above you, and
;;   then re-computing all the merges that aren't being undone

(defn- ruminate-merging [layer [merge-layer] keyseq f args]
  (fn [read]
    #_(-> (if-let [head (merge-head read merge-layer (get-id keyseq))]
          (let [keyseq (update keyseq for head-id)]
            (apply compose (for [layer [layer (child layer :phantom)]]
                             (update-in-node layer keyseq f args))))
          (update-in-node layer keyseq f args))
        (invoke read))))

(defn merged
  "layers needs to be a map of layer names to base layers. The base layer will be used to store a
   merged view of tha data written to the merging layer, as determined by merges written to the
   merge-layer. Each base layer must have a child named :phantom, which will be used to store
   internal bookkeeping data, and should not be used by client code.

   Will return a list, [new-merge-layer [merging-layer1 merging-layer2 ...]].

   Writes to these returned layers will automatically update each other as needed to keep the merged
   views consistent."
  [merge-layer layers]
  [(ruminate/make merge-layer layers ruminate-merge)
   (for [layer layers]
     (ruminate/make layer [merge-layer] ruminate-merging))])


#_(merged m [(parent/make tree-base {:phantom tree-phantom})
             (parent/make data-base {:phantom data-phantom})])
