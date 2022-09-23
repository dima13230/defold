;; Copyright 2020-2022 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;; 
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;; 
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.collection
  (:require [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.build-target :as bt]
            [editor.code.script :as script]
            [editor.collection-common :as collection-common]
            [editor.core :as core]
            [editor.defold-project :as project]
            [editor.game-object :as game-object]
            [editor.game-object-common :as game-object-common]
            [editor.geom :as geom]
            [editor.gl.pass :as pass]
            [editor.graph-util :as gu]
            [editor.handler :as handler]
            [editor.outline :as outline]
            [editor.properties :as properties]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.resource-dialog :as resource-dialog]
            [editor.resource-node :as resource-node]
            [editor.scene :as scene]
            [editor.validation :as validation]
            [editor.workspace :as workspace]
            [internal.cache :as c])
  (:import [com.dynamo.gameobject.proto GameObject$CollectionDesc GameObject$PrototypeDesc]
           [internal.graph.types Arc]))

(set! *warn-on-reflection* true)

(defn- gen-embed-ddf [id child-ids position rotation scale proto-msg]
  {:id id
   :children (sort child-ids)
   :data (protobuf/map->str GameObject$PrototypeDesc proto-msg false)
   :position position
   :rotation rotation
   :scale3 scale})

(defn- gen-ref-ddf [id child-ids position rotation scale path ddf-component-properties]
  {:id id
   :children (sort child-ids)
   :prototype (resource/resource->proj-path path)
   :position position
   :rotation rotation
   :scale3 scale
   :component-properties ddf-component-properties})

(g/defnode InstanceNode
  (inherits outline/OutlineNode)
  (inherits core/Scope)
  (property id g/Str
            (dynamic error (g/fnk [_node-id id id-counts]
                             (validation/prop-error :fatal _node-id :id (partial validation/prop-id-duplicate? id-counts) id)))
            (dynamic read-only? (g/fnk [_node-id]
                                  (g/override? _node-id))))
  (property url g/Str
            (value (g/fnk [base-url id] (format "%s/%s" (or base-url "") id)))
            (dynamic read-only? (g/constantly true)))
  (input base-url g/Str)
  (input source-id g/NodeID :cascade-delete)
  (input id-counts g/Any)

  (input resource-property-build-targets g/Any)
  (output resource-property-build-targets g/Any (gu/passthrough resource-property-build-targets)))

(defn- child-go-go [go-id child-id]
  ;; go-id: EmbeddedGOInstanceNode, ReferencedGOInstanceNode
  ;; child-id: EmbeddedGOInstanceNode, ReferencedGOInstanceNode
  (for [[from to] [[:_node-id :nodes]
                   [:id :child-ids]
                   [:node-outline :child-outlines]
                   [:scene :child-scenes]]]
    (g/connect child-id from go-id to)))

(defn- child-coll-any [coll-id child-id]
  ;; coll-id: CollectionNode
  ;; child-id: CollectionInstanceNode, EmbeddedGOInstanceNode, ReferencedGOInstanceNode
  (for [[from to] [[:_node-id :nodes]
                   [:node-outline :child-outlines]
                   [:scene :child-scenes]]]
    (g/connect child-id from coll-id to)))

(defn- attach-coll-go [coll-id child-id ddf-label]
  ;; coll-id: CollectionNode
  ;; child-id: EmbeddedGOInstanceNode, ReferencedGOInstanceNode
  (concat
    (for [[from to] [[:ddf-message ddf-label]
                     [:build-targets :dep-build-targets]
                     [:id :ids]
                     [:go-inst-ids :go-inst-ids]
                     [:ddf-properties :ddf-properties]
                     [:resource-property-build-targets :resource-property-build-targets]]]
      (g/connect child-id from coll-id to))
    (for [[from to] [[:base-url :base-url]
                     [:id-counts :id-counts]]]
      (g/connect coll-id from child-id to))))

(defn- attach-coll-ref-go [coll-id child-id]
  ;; coll-id: CollectionNode
  ;; child-id: ReferencedGOInstanceNode
  (attach-coll-go coll-id child-id :ref-inst-ddf))

(defn- attach-coll-embedded-go [coll-id child-id]
  ;; coll-id: CollectionNode
  ;; child-id: EmbeddedGOInstanceNode
  (attach-coll-go coll-id child-id :embed-inst-ddf))

(defn- attach-coll-coll [coll-id child-id]
  ;; coll-id: CollectionNode
  ;; child-id: CollectionInstanceNode
  (concat
    (for [[from to] [[:_node-id :nodes]
                     [:ddf-message :ref-coll-ddf]
                     [:id :ids]
                     [:build-targets :sub-build-targets]
                     [:go-inst-ids :go-inst-ids]
                     [:sub-ddf-properties :ddf-properties]
                     [:resource-property-build-targets :resource-property-build-targets]]]
      (g/connect child-id from coll-id to))
    (for [[from to] [[:base-url :base-url]
                     [:id-counts :id-counts]]]
      (g/connect coll-id from child-id to))))

(declare EmbeddedGOInstanceNode ReferencedGOInstanceNode CollectionNode)

(defn- go-id->node-ids [go-id]
  (let [collection (core/scope-of-type go-id CollectionNode)]
    (g/node-value collection :ids)))

(g/defnk produce-go-outline [_node-id id source-outline source-resource child-outlines node-outline-extras]
  (-> {:node-id _node-id
       :node-outline-key id
       :label id
       :icon (or (not-empty (:icon source-outline)) game-object-common/game-object-icon)
       :children (into (outline/natural-sort child-outlines) (:children source-outline))
       :child-reqs [{:node-type ReferencedGOInstanceNode
                     :tx-attach-fn (fn [self-id child-id]
                                     (let [coll-id (core/scope-of-type self-id CollectionNode)]
                                       (concat
                                         (g/update-property child-id :id outline/resolve-id (go-id->node-ids self-id))
                                         (attach-coll-ref-go coll-id child-id)
                                         (child-go-go self-id child-id))))}
                    {:node-type EmbeddedGOInstanceNode
                     :tx-attach-fn (fn [self-id child-id]
                                     (let [coll-id (core/scope-of-type self-id CollectionNode)]
                                       (concat
                                         (g/update-property child-id :id outline/resolve-id (go-id->node-ids self-id))
                                         (attach-coll-embedded-go coll-id child-id)
                                         (child-go-go self-id child-id))))}]}
      (merge node-outline-extras)
      (cond->
        (resource/openable-resource? source-resource) (assoc :link source-resource :outline-reference? true))))

(defn- source-outline-subst [err]
  ;; TODO: embed error so can warn in outline
  ;; outline content not really used, only children if any.
  {:node-id 0
   :node-outline-key ""
   :icon ""
   :label ""})

(g/defnk produce-go-build-targets [_node-id build-error build-resource ddf-message resource-property-build-targets source-build-targets transform]
  ;; Create a build-target for the referenced or embedded game object. Also tag
  ;; on :instance-data with the overrides for this instance. This will later be
  ;; extracted and compiled into the Collection - the overrides do not end up in
  ;; the resulting game object binary.
  ;; Please refer to `/engine/gameobject/proto/gameobject/gameobject_ddf.proto`
  ;; when reading this. It describes how the ddf-message map is structured.
  ;; You might also want to familiarize yourself with how this process works in
  ;; `game_object.clj`, since it is similar but less complicated there.
  (if-some [errors
            (not-empty
              (sequence (comp (mapcat :properties) ; Extract PropertyDescs from ComponentPropertyDescs
                              (keep :error))
                        (:component-properties ddf-message)))]
    (g/error-aggregate errors :_node-id _node-id :_label :build-targets)
    (let [game-object-build-target (first source-build-targets)
          proj-path->resource-property-build-target (bt/make-proj-path->build-target resource-property-build-targets)]
      [(collection-common/game-object-instance-build-target build-resource ddf-message transform game-object-build-target proj-path->resource-property-build-target)])))

(g/defnode GameObjectInstanceNode
  (inherits scene/SceneNode)
  (inherits InstanceNode)

  (input properties g/Any)
  (input source-build-targets g/Any)
  (input source-resource resource/Resource)
  (input scene g/Any)
  (input child-scenes g/Any :array)
  (input child-ids g/Str :array)

  (input ddf-component-properties g/Any :substitute [])
  (input source-outline outline/OutlineData :substitute source-outline-subst)
  (output source-outline outline/OutlineData (gu/passthrough source-outline))

  (output transform-properties g/Any scene/produce-scalable-transform-properties)
  (output node-outline outline/OutlineData :cached produce-go-outline)
  (output ddf-message g/Any :abstract)
  (output node-outline-extras g/Any (g/constantly {}))
  (output build-resource resource/Resource :abstract)
  (output build-targets g/Any produce-go-build-targets)
  (output build-error g/Err (g/constantly nil))

  (output scene g/Any :cached (g/fnk [_node-id id transform scene child-scenes]
                                     (-> (scene/claim-scene scene _node-id id)
                                         (assoc :transform transform
                                                :aabb geom/empty-bounding-box
                                                :renderable {:passes [pass/selection]})
                                         (update :children (fn [s] (reduce conj (or s []) child-scenes))))))
  (output go-inst-ids g/Any (g/fnk [_node-id id] {id _node-id}))
  (output ddf-properties g/Any (g/fnk [id ddf-component-properties] {:id id :properties ddf-component-properties})))

(g/defnode EmbeddedGOInstanceNode
  (inherits GameObjectInstanceNode)

  (display-order [:id :url scene/SceneNode])

  (input proto-msg g/Any)
  (input source-save-data g/Any)
  (output node-outline-extras g/Any (g/fnk [source-outline]
                                           {:alt-outline source-outline}))
  (output build-resource resource/Resource (g/fnk [source-resource source-save-data]
                                                  (some-> source-resource
                                                     (assoc :data (:content source-save-data))
                                                     workspace/make-build-resource)))
  (output ddf-message g/Any (g/fnk [id child-ids position rotation scale proto-msg]
                              (gen-embed-ddf id child-ids position rotation scale proto-msg))))

(defn- component-resource [comp-id basis]
  (when (g/node-instance? basis game-object/ComponentNode comp-id)
    (g/node-value comp-id :source-resource (g/make-evaluation-context {:basis basis :cache c/null-cache}))))

(defn- overridable-component? [basis node-id]
  (some-> node-id
    (component-resource basis)
    resource/resource-type
    :tags
    (contains? :overridable-properties)))

(def ^:private or-go-traverse-fn
  (g/make-override-traverse-fn
    (fn or-go-traverse-fn [basis ^Arc arc]
      (let [source-node-id (.source-id arc)]
        (or (overridable-component? basis source-node-id)
            (g/node-instance-of-any? basis source-node-id
                                     [resource-node/ResourceNode
                                      script/ScriptPropertyNode]))))))

(defn- path-error [node-id resource]
  (or (validation/prop-error :fatal node-id :path validation/prop-nil? resource "Path")
      (validation/prop-error :fatal node-id :path validation/prop-resource-not-exists? resource "Path")))

(defn- substitute-error [val-or-error substitute]
  (if-not (g/error? val-or-error)
    val-or-error
    substitute))

(g/defnode ReferencedGOInstanceNode
  (inherits GameObjectInstanceNode)

  (property path g/Any
            (dynamic edit-type (g/fnk [source-resource]
                                      {:type resource/Resource
                                       :ext (some-> source-resource resource/resource-type :ext)
                                       :to-type (fn [v] (:resource v))
                                       :from-type (fn [r] {:resource r :overrides []})}))
            (value (g/fnk [source-resource ddf-component-properties]
                          {:resource source-resource
                           :overrides ddf-component-properties}))
            (set (fn [evaluation-context self old-value new-value]
                   (concat
                     (if-let [old-source (g/node-value self :source-id evaluation-context)]
                       (g/delete-node old-source)
                       [])
                     (let [new-resource (:resource new-value)
                           basis (:basis evaluation-context)
                           project (project/get-project basis self)
                           workspace (project/workspace project)]
                       (when-some [{connect-tx-data :tx-data go-node :node-id} (project/connect-resource-node evaluation-context project new-resource self [])]
                         (concat
                           connect-tx-data
                           ;; TODO: Don't override immutable game objects.
                           (g/override go-node {:traverse-fn or-go-traverse-fn}
                                       (fn [evaluation-context id-mapping]
                                         (let [or-go-node (get id-mapping go-node)
                                               comp-name->refd-comp-node (g/node-value go-node :component-ids evaluation-context)]
                                           (concat
                                             (for [[from to] [[:_node-id                        :source-id]
                                                              [:resource                        :source-resource]
                                                              [:node-outline                    :source-outline]
                                                              [:scene                           :scene]
                                                              [:ddf-component-properties        :ddf-component-properties]
                                                              [:resource-property-build-targets :resource-property-build-targets]]]
                                               (g/connect or-go-node from self to))
                                             (for [[from to] [[:build-targets :source-build-targets]]]
                                               (g/connect go-node from self to))
                                             (for [[from to] [[:url :base-url]]]
                                               (g/connect self from or-go-node to))
                                             (for [{comp-name :id overrides :properties} (:overrides new-value)
                                                   :let [refd-comp-node (comp-name->refd-comp-node comp-name)
                                                         comp-props (:properties (g/node-value refd-comp-node :_properties evaluation-context))]]
                                               (properties/apply-property-overrides workspace id-mapping comp-props overrides))))))))))))
            (dynamic error (g/fnk [_node-id source-resource]
                                  (path-error _node-id source-resource))))

  (display-order [:id :url :path scene/SceneNode])

  (output ddf-message g/Any (g/fnk [id child-ids source-resource position rotation scale ddf-component-properties]
                                   (gen-ref-ddf id child-ids position rotation scale source-resource ddf-component-properties)))
  (output build-error g/Err (g/fnk [_node-id source-resource]
                                   (path-error _node-id source-resource)))
  (output build-resource resource/Resource (g/fnk [source-build-targets]
                                             (:resource (first source-build-targets)))))

(g/defnk produce-proto-msg [name scale-along-z ref-inst-ddf embed-inst-ddf ref-coll-ddf]
  {:name name
   :scale-along-z (if scale-along-z 1 0)
   :instances ref-inst-ddf
   :embedded-instances embed-inst-ddf
   :collection-instances ref-coll-ddf})

(g/defnk produce-save-value [proto-msg]
  (update proto-msg :embedded-instances
          (fn [embedded-instance-descs]
            (mapv (fn [embedded-instance-desc]
                    (update embedded-instance-desc :data
                            (fn [string-encoded-prototype-desc]
                              (-> (protobuf/str->map GameObject$PrototypeDesc string-encoded-prototype-desc)
                                  (game-object/strip-default-scale-from-components-in-prototype-desc)
                                  (as-> prototype-desc
                                        (protobuf/map->str GameObject$PrototypeDesc prototype-desc false))))))
                  embedded-instance-descs))))

(g/defnk produce-build-targets [_node-id name resource sub-build-targets dep-build-targets id-counts scale-along-z]
  (or (let [dup-ids (keep (fn [[id count]] (when (> count 1) id)) id-counts)]
        (game-object-common/maybe-duplicate-id-error _node-id dup-ids))
      (let [build-resource (workspace/make-build-resource resource)]
        [(collection-common/collection-build-target build-resource _node-id name scale-along-z dep-build-targets sub-build-targets)])))

(declare CollectionInstanceNode)

(g/defnk produce-coll-outline [_node-id child-outlines]
  (let [[go-outlines coll-outlines] (let [outlines (group-by #(g/node-instance? CollectionInstanceNode (:node-id %)) child-outlines)]
                                      [(get outlines false) (get outlines true)])]
    {:node-id _node-id
     :node-outline-key "Collection"
     :label "Collection"
     :icon collection-common/collection-icon
     :children (into (outline/natural-sort coll-outlines) (outline/natural-sort go-outlines))
     :child-reqs [{:node-type ReferencedGOInstanceNode
                   :tx-attach-fn (fn [self-id child-id]
                                   (concat
                                     (g/update-property child-id :id outline/resolve-id (g/node-value self-id :ids))
                                     (attach-coll-ref-go self-id child-id)
                                     (child-coll-any self-id child-id)))}
                  {:node-type EmbeddedGOInstanceNode
                   :tx-attach-fn (fn [self-id child-id]
                                   (concat
                                     (g/update-property child-id :id outline/resolve-id (g/node-value self-id :ids))
                                     (attach-coll-embedded-go self-id child-id)
                                     (child-coll-any self-id child-id)))}
                  {:node-type CollectionInstanceNode
                   :tx-attach-fn (fn [self-id child-id]
                                   (concat
                                     (g/update-property child-id :id outline/resolve-id (g/node-value self-id :ids))
                                     (attach-coll-coll self-id child-id)
                                     (child-coll-any self-id child-id)))}]}))

(g/defnode CollectionNode
  (inherits resource-node/ResourceNode)

  (property name g/Str)
  ;; This property is legacy and purposefully hidden
  ;; The feature is only useful for uniform scaling, we use non-uniform now
  (property scale-along-z g/Bool
            (dynamic visible (g/constantly false)))

  (input ref-inst-ddf g/Any :array)
  (input embed-inst-ddf g/Any :array)
  (input ref-coll-ddf g/Any :array)
  (input child-scenes g/Any :array)
  (input ids g/Str :array)
  (input sub-build-targets g/Any :array)
  (input dep-build-targets g/Any :array)
  (input base-url g/Str)
  (input go-inst-ids g/Any :array)
  (input ddf-properties g/Any :array)
  (input resource-property-build-targets g/Any :array)

  (output resource-property-build-targets g/Any (gu/passthrough resource-property-build-targets))
  (output base-url g/Str (gu/passthrough base-url))
  (output proto-msg g/Any produce-proto-msg)
  (output save-value g/Any :cached produce-save-value)
  (output build-targets g/Any :cached produce-build-targets)
  (output node-outline outline/OutlineData :cached produce-coll-outline)
  (output scene g/Any :cached (g/fnk [_node-id child-scenes]
                                     {:node-id _node-id
                                      :children child-scenes
                                      :aabb geom/null-aabb}))
  (output go-inst-ids g/Any :cached (g/fnk [go-inst-ids] (reduce merge {} go-inst-ids)))
  (output ddf-properties g/Any (g/fnk [ddf-properties] (reduce (fn [props m]
                                                                 (if (empty? (:properties m))
                                                                   props
                                                                   (conj props m)))
                                                               [] (flatten ddf-properties))))
  (output id-counts g/Any :cached (g/fnk [ids]
                                         (reduce (fn [res id]
                                                   (update res id (fn [id] (inc (or id 0)))))
                                                 {} ids))))

(g/defnk produce-coll-inst-build-targets [_node-id source-resource id transform build-targets resource-property-build-targets ddf-properties]
  (if-some [errors
            (not-empty
              (concat
                (some-> (path-error _node-id source-resource) vector)
                (sequence (comp (mapcat :properties) ; Extract ComponentPropertyDescs from InstancePropertyDescs
                                (mapcat :properties) ; Extract PropertyDescs from ComponentPropertyDescs
                                (keep :error))
                          ddf-properties)))]
    (g/error-aggregate errors :_node-id _node-id :_label :build-targets)
    (let [proj-path->resource-property-build-target (bt/make-proj-path->build-target resource-property-build-targets)]
      (update build-targets 0
              (fn [collection-build-target]
                (collection-common/collection-instance-build-target id transform ddf-properties collection-build-target proj-path->resource-property-build-target))))))

(g/defnk produce-coll-inst-outline [_node-id id source-resource source-outline source-id source-resource]
  (-> {:node-id _node-id
       :node-outline-key id
       :label id
       :icon (or (not-empty (:icon source-outline)) collection-common/collection-icon)
       :children (:children source-outline)}
    (cond->
      (resource/openable-resource? source-resource)
      (assoc :link source-resource
             :outline-reference? true
             :alt-outline source-outline))))

(def ^:private or-coll-traverse-fn
  (g/make-override-traverse-fn
    (fn or-coll-traverse-fn [basis ^Arc arc]
      (let [source-node-id (.source-id arc)]
        (or (overridable-component? basis source-node-id)
            (g/node-instance-of-any? basis source-node-id
                                     [resource-node/ResourceNode
                                      script/ScriptPropertyNode
                                      InstanceNode]))))))

(g/defnode CollectionInstanceNode
  (inherits scene/SceneNode)
  (inherits InstanceNode)

  (property path g/Any
    (value (g/fnk [source-resource ddf-properties]
                  {:resource source-resource
                   :overrides ddf-properties}))
    (set (fn [evaluation-context self _old-value new-value]
           (concat
             (if-let [old-source (g/node-value self :source-id evaluation-context)]
               (g/delete-node old-source)
               [])
             (let [new-resource (:resource new-value)
                   basis (:basis evaluation-context)
                   project (project/get-project basis self)
                   workspace (project/workspace project)]
               (when-some [{connect-tx-data :tx-data coll-node :node-id} (project/connect-resource-node evaluation-context project new-resource self [])]
                 (concat
                   connect-tx-data
                   ;; TODO: Don't override immutable collections.
                   (g/override coll-node {:traverse-fn or-coll-traverse-fn}
                               (fn [evaluation-context id-mapping]
                                 (let [or-coll-node (get id-mapping coll-node)
                                       go-name->go-node (comp #(g/node-value % :source-id evaluation-context)
                                                              (g/node-value coll-node :go-inst-ids evaluation-context))]
                                   (concat
                                     (for [[from to] [[:_node-id                        :source-id]
                                                      [:resource                        :source-resource]
                                                      [:node-outline                    :source-outline]
                                                      [:scene                           :scene]
                                                      [:ddf-properties                  :ddf-properties]
                                                      [:go-inst-ids                     :go-inst-ids]
                                                      [:resource-property-build-targets :resource-property-build-targets]]]
                                       (g/connect or-coll-node from self to))
                                     (for [[from to] [[:build-targets :build-targets]]]
                                       (g/connect coll-node from self to))
                                     (for [[from to] [[:url :base-url]]]
                                       (g/connect self from or-coll-node to))
                                     (for [{go-name :id overrides :properties} (:overrides new-value)
                                           :let [go-node (go-name->go-node go-name)
                                                 comp-name->refd-comp-node (g/node-value go-node :component-ids evaluation-context)]
                                           {comp-name :id overrides :properties} overrides
                                           :let [refd-comp-node (comp-name->refd-comp-node comp-name)
                                                 comp-props (:properties (g/node-value refd-comp-node :_properties evaluation-context))]]
                                       (properties/apply-property-overrides workspace id-mapping comp-props overrides))))))))))))
    (dynamic error (g/fnk [_node-id source-resource]
                          (path-error _node-id source-resource)))
    (dynamic edit-type (g/fnk [source-resource]
                              {:type resource/Resource
                               :ext "collection"
                               :to-type (fn [v] (:resource v))
                               :from-type (fn [r] {:resource r :overrides []})})))

  (display-order [:id :url :path scene/SceneNode])

  (input source-resource resource/Resource)
  (input ddf-properties g/Any :substitute [])
  (input scene g/Any)
  (input build-targets g/Any)
  (input go-inst-ids g/Any)

  (input source-outline outline/OutlineData :substitute source-outline-subst)
  (output source-outline outline/OutlineData (gu/passthrough source-outline))

  (output transform-properties g/Any scene/produce-scalable-transform-properties)
  (output node-outline outline/OutlineData :cached produce-coll-inst-outline)
  (output ddf-message g/Any (g/fnk [id source-resource position rotation scale ddf-properties]
                                   {:id id
                                    :collection (resource/resource->proj-path source-resource)
                                    :position position
                                    :rotation rotation
                                    :scale3 scale
                                    :instance-properties ddf-properties}))
  (output scene g/Any :cached (g/fnk [_node-id id transform scene]
                                     (assoc (scene/claim-scene scene _node-id id)
                                            :transform transform
                                            :aabb geom/empty-bounding-box
                                            :renderable {:passes [pass/selection]})))
  (output build-targets g/Any produce-coll-inst-build-targets)
  (output sub-ddf-properties g/Any :cached (g/fnk [id ddf-properties]
                                                  (map (fn [m] (update m :id (fn [s] (format "%s/%s" id s)))) ddf-properties)))
  (output go-inst-ids g/Any :cached (g/fnk [id go-inst-ids] (into {} (map (fn [[k v]] [(format "%s/%s" id k) v]) go-inst-ids)))))

(defn- gen-instance-id [coll-node base]
  (let [ids (g/node-value coll-node :ids)]
    (loop [postfix 0]
      (let [id (if (= postfix 0) base (str base postfix))]
        (if (empty? (filter #(= id %) ids))
          id
          (recur (inc postfix)))))))


(defn- make-ref-go [self project source-resource id position rotation scale parent overrides]
  (let [path {:resource source-resource
              :overrides overrides}]
    (g/make-nodes (g/node-id->graph-id self)
                  [go-node [ReferencedGOInstanceNode :id id :path path :position position :rotation rotation :scale scale]]
                  (attach-coll-ref-go self go-node)
                  (if parent
                    (if (= self parent)
                      (child-coll-any self go-node)
                      (child-go-go parent go-node))
                    []))))

(defn- selection->collection [selection]
  (g/override-root (if-some [collection-instance (handler/adapt-single selection CollectionInstanceNode)]
                     (g/node-feeding-into collection-instance :source-resource)
                     (handler/adapt-single selection CollectionNode))))

(defn- selection->game-object-instance [selection]
  (g/override-root (handler/adapt-single selection GameObjectInstanceNode)))

(defn add-game-object-file [coll-node parent resource select-fn]
  (let [project (project/get-project coll-node)
        base (resource/base-name resource)
        id (gen-instance-id coll-node base)
        op-seq (gensym)
        [go-node] (g/tx-nodes-added
                    (g/transact
                      (concat
                        (g/operation-label "Add Game Object")
                        (g/operation-sequence op-seq)
                        (make-ref-go coll-node project resource id [0.0 0.0 0.0] [0.0 0.0 0.0 1.0] [1.0 1.0 1.0] parent []))))]
    ;; Selection
    (g/transact
      (concat
        (g/operation-sequence op-seq)
        (g/operation-label "Add Game Object")
        (select-fn [go-node])))))

(defn- select-go-file [workspace project]
  (first (resource-dialog/make workspace project {:ext "go" :title "Select Game Object File"})))

(handler/defhandler :add-from-file :workbench
  (active? [selection] (selection->collection selection))
  (label [selection] "Add Game Object File")
  (run [workspace project app-view selection]
    (let [collection (selection->collection selection)]
      (when-let [resource (first (resource-dialog/make workspace project {:ext "go" :title "Select Game Object File"}))]
        (add-game-object-file collection collection resource (fn [node-ids] (app-view/select app-view node-ids)))))))

(defn- make-embedded-go [self project type data id position rotation scale parent select-fn]
  (let [graph (g/node-id->graph-id self)
        resource (project/make-embedded-resource project type data)
        node-type (project/resource-node-type resource)]
    (g/make-nodes graph [go-node [EmbeddedGOInstanceNode :id id :position position :rotation rotation :scale scale]
                         resource-node [node-type :resource resource]]
                  (g/connect go-node :url resource-node :base-url)
                  (project/load-node project resource-node node-type resource)
                  (project/connect-if-output node-type resource-node go-node
                                             [[:_node-id :source-id]
                                              [:resource :source-resource]
                                              [:node-outline :source-outline]
                                              [:proto-msg :proto-msg]
                                              [:undecorated-save-data :source-save-data]
                                              [:build-targets :source-build-targets]
                                              [:scene :scene]
                                              [:ddf-component-properties :ddf-component-properties]
                                              [:resource-property-build-targets :resource-property-build-targets]])
                  (attach-coll-embedded-go self go-node)
                  (when parent
                    (if (= parent self)
                      (child-coll-any self go-node)
                      (child-go-go parent go-node)))
                  (when select-fn
                    (select-fn [go-node])))))

(defn add-game-object [workspace project coll-node parent select-fn]
  (let [ext           "go"
        resource-type (workspace/get-resource-type workspace ext)
        template      (workspace/template workspace resource-type)
        id (gen-instance-id coll-node ext)]
    (g/transact
      (concat
        (g/operation-label "Add Game Object")
        (make-embedded-go coll-node project ext template id [0.0 0.0 0.0] [0.0 0.0 0.0 1.0] [1.0 1.0 1.0] parent select-fn)))))

(handler/defhandler :add :workbench
  (active? [selection] (selection->collection selection))
  (label [selection user-data] "Add Game Object")
  (run [selection workspace project user-data app-view]
    (let [collection (selection->collection selection)]
      (add-game-object workspace project collection collection (fn [node-ids] (app-view/select app-view node-ids))))))

(defn add-collection-instance [self source-resource id position rotation scale overrides]
  (let [project (project/get-project self)]
    (g/make-nodes (g/node-id->graph-id self)
                  [coll-node [CollectionInstanceNode :id id :path {:resource source-resource :overrides overrides}
                              :position position :rotation rotation :scale scale]]
                  (attach-coll-coll self coll-node)
                  (child-coll-any self coll-node))))

(handler/defhandler :add-secondary :workbench
  (active? [selection] (selection->game-object-instance selection))
  (label [] "Add Game Object")
  (run [selection project workspace app-view]
       (let [go-node (selection->game-object-instance selection)
             collection (core/scope-of-type go-node CollectionNode)]
         (add-game-object workspace project collection go-node (fn [node-ids] (app-view/select app-view node-ids))))))

(handler/defhandler :add-secondary-from-file :workbench
  (active? [selection] (or (selection->collection selection)
                         (selection->game-object-instance selection)))
  (label [selection] (if (selection->collection selection)
                       "Add Collection File"
                       "Add Game Object File"))
  (run [selection workspace project app-view]
       (if-let [coll-node (selection->collection selection)]
         (let [ext           "collection"
               resource-type (workspace/get-resource-type workspace ext)
               coll-node-path (resource/proj-path (g/node-value coll-node :resource))
               accept (fn [x] (not= (resource/proj-path x) coll-node-path))]
           (when-let [resource (first (resource-dialog/make workspace project {:ext ext :title "Select Collection File" :accept-fn accept}))]
             (let [base (resource/base-name resource)
                   id (gen-instance-id coll-node base)
                   op-seq (gensym)
                   [coll-inst-node] (g/tx-nodes-added
                                      (g/transact
                                        (concat
                                          (g/operation-label "Add Collection")
                                          (g/operation-sequence op-seq)
                                          (add-collection-instance coll-node resource id [0.0 0.0 0.0] [0.0 0.0 0.0 1.0] [1.0 1.0 1.0] []))))]
               ; Selection
               (g/transact
                 (concat
                   (g/operation-sequence op-seq)
                   (g/operation-label "Add Collection")
                   (app-view/select app-view [coll-inst-node]))))))
         (when-let [resource (select-go-file workspace project)]
           (let [go-node (selection->game-object-instance selection)
                 coll-node (core/scope-of-type go-node CollectionNode)]
             (add-game-object-file coll-node go-node resource (fn [node-ids] (app-view/select app-view node-ids))))))))

(defn load-collection [project self resource collection]
  (concat
    (g/set-property self :name (:name collection))
    (g/set-property self :scale-along-z (not= 0 (:scale-along-z collection)))
    (let [tx-go-creation (flatten
                           (concat
                             (for [game-object (:instances collection)
                                   :let [source-resource (workspace/resolve-resource resource (:prototype game-object))]]
                               (make-ref-go self project source-resource (:id game-object) (:position game-object)
                                            (:rotation game-object) (:scale3 game-object) nil (:component-properties game-object)))
                             (for [embedded (:embedded-instances collection)]
                               (make-embedded-go self project "go" (:data embedded) (:id embedded)
                                                 (:position embedded)
                                                 (:rotation embedded)
                                                 (:scale3 embedded)
                                                 nil nil))))
          new-instance-data (filter #(and (= :create-node (:type %)) (g/node-instance*? GameObjectInstanceNode (:node %))) tx-go-creation)
          id->nid (into {} (map #(do [(get-in % [:node :id]) (g/node-id (:node %))]) new-instance-data))
          child->parent (into {} (map #(do [% nil]) (keys id->nid)))
          rev-child-parent-fn (fn [instances] (into {} (mapcat (fn [inst] (map #(do [% (:id inst)]) (:children inst))) instances)))
          child->parent (merge child->parent (rev-child-parent-fn (concat (:instances collection) (:embedded-instances collection))))]
      (concat
        tx-go-creation
        (for [[child parent] child->parent
              :let [child-id (id->nid child)
                    parent-id (if parent (id->nid parent) self)]]
          (if parent
            (child-go-go parent-id child-id)
            (child-coll-any self child-id)))))
    (for [coll-instance (:collection-instances collection)
          :let [source-resource (workspace/resolve-resource resource (:collection coll-instance))]]
      (add-collection-instance self source-resource (:id coll-instance) (:position coll-instance)
                               (:rotation coll-instance) (:scale3 coll-instance) (:instance-properties coll-instance)))))

(defn- sanitize-collection [workspace collection-desc]
  (let [ext->resource-type (workspace/get-resource-type-map workspace)]
    (collection-common/sanitize-collection-desc collection-desc ext->resource-type :embed-data-as-strings)))

(defn register-resource-types [workspace]
  (resource-node/register-ddf-resource-type workspace
    :ext "collection"
    :label "Collection"
    :node-type CollectionNode
    :ddf-type GameObject$CollectionDesc
    :load-fn load-collection
    :dependencies-fn (collection-common/make-collection-dependencies-fn workspace)
    :sanitize-fn (partial sanitize-collection workspace)
    :icon collection-common/collection-icon
    :view-types [:scene :text]
    :view-opts {:scene {:grid true}}))
