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

(ns editor.game-object-common
  (:require [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.build-target :as bt]
            [editor.properties :as properties]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.workspace :as workspace]
            [internal.util :as util]
            [service.log :as log])
  (:import [com.dynamo.gameobject.proto GameObject$PrototypeDesc]
           [java.io StringReader]
           [javax.vecmath Matrix4d]))

(set! *warn-on-reflection* true)

(def game-object-icon "icons/32/Icons_06-Game-object.png")

(defn sanitize-property-descs-at-path [any-desc property-descs-path]
  ;; GameObject$ComponentDesc, GameObject$InstanceDesc, GameObject$EmbeddedInstanceDesc, or GameObject$CollectionInstanceDesc in map format.
  ;; The supplied path should lead up to a seq of GameObject$PropertyDescs in map format.
  (if-some [property-descs (not-empty (get-in any-desc property-descs-path))]
    (assoc-in any-desc property-descs-path (mapv properties/sanitize-property-desc property-descs))
    any-desc))

(def identity-transform-properties
  {:position [(float 0.0) (float 0.0) (float 0.0)]
   :rotation [(float 0.0) (float 0.0) (float 0.0) (float 1.0)]
   :scale [(float 1.0) (float 1.0) (float 1.0)]})

(def ^:private default-scale-value (:scale identity-transform-properties))

(defn strip-default-scale-from-component-desc [component-desc]
  ;; GameObject$ComponentDesc or GameObject$EmbeddedComponentDesc in map format.
  (let [scale (:scale component-desc)]
    (if (or (= default-scale-value scale)
            (protobuf/default-read-scale-value? scale))
      (dissoc component-desc :scale)
      component-desc)))

(defn- sanitize-component-desc [component-desc]
  ;; GameObject$ComponentDesc in map format.
  (-> component-desc
      (sanitize-property-descs-at-path [:properties])
      (dissoc :property-decls) ; Only used in built data by the runtime.
      (strip-default-scale-from-component-desc)))

(defn- sanitize-embedded-component-data [embedded-component-desc resource-type-map embed-data-handling]
  ;; GameObject$EmbeddedComponentDesc in map format.
  (let [component-ext (:type embedded-component-desc)
        resource-type (resource-type-map component-ext)
        tag-opts (:tag-opts resource-type)
        sanitize-fn (:sanitize-fn resource-type)
        sanitize-embedded-component-fn (:sanitize-embedded-component-fn (:component tag-opts))]
    (try
      (let [unsanitized-data (when (or sanitize-fn
                                       sanitize-embedded-component-fn
                                       (= :embed-data-as-maps embed-data-handling))
                               (let [read-raw-fn (:read-raw-fn resource-type)
                                     unsanitized-data-string (:data embedded-component-desc)]
                                 (with-open [reader (StringReader. unsanitized-data-string)]
                                   (read-raw-fn reader))))
            sanitized-data (if sanitize-fn
                             (sanitize-fn unsanitized-data)
                             unsanitized-data)
            [embedded-component-desc sanitized-data] (if sanitize-embedded-component-fn
                                                       (sanitize-embedded-component-fn embedded-component-desc sanitized-data)
                                                       [embedded-component-desc sanitized-data])]
        (case embed-data-handling
          :embed-data-as-maps (assoc embedded-component-desc :data sanitized-data)
          :embed-data-as-strings (if (= unsanitized-data sanitized-data)
                                   embedded-component-desc ; No change after sanitization - we can use the original string.
                                   (let [write-fn (:write-fn resource-type)
                                         sanitized-data-string (write-fn sanitized-data)]
                                     (assoc embedded-component-desc :data sanitized-data-string)))))
      (catch Exception error
        ;; Leave unsanitized.
        (log/warn :msg (str "Failed to sanitize embedded component of type: " (or component-ext "nil")) :exception error)
        embedded-component-desc))))

(defn- sanitize-embedded-component-desc [embedded-component-desc ext->resource-type embed-data-handling]
  ;; GameObject$EmbeddedComponentDesc in map format.
  (-> embedded-component-desc
      (sanitize-embedded-component-data ext->resource-type embed-data-handling)
      (strip-default-scale-from-component-desc)))

(defn sanitize-prototype-desc [prototype-desc ext->resource-type embed-data-handling]
  {:pre [(map? prototype-desc)
         (ifn? ext->resource-type)
         (case embed-data-handling (:embed-data-as-maps :embed-data-as-strings) true false)]}
  ;; GameObject$PrototypeDesc in map format.
  (-> prototype-desc
      (update :components (partial mapv sanitize-component-desc))
      (update :embedded-components (partial mapv #(sanitize-embedded-component-desc % ext->resource-type embed-data-handling)))))

(defn any-descs->duplicate-ids [any-instance-descs]
  ;; GameObject$ComponentDesc, GameObject$EmbeddedComponentDesc, GameObject$InstanceDesc, GameObject$EmbeddedInstanceDesc, or GameObject$CollectionInstanceDesc in map format.
  (into (sorted-set)
        (keep (fn [[id count]]
                (when (> count 1)
                  id)))
        (frequencies
          (map :id
               any-instance-descs))))

(defn maybe-duplicate-id-error [node-id duplicate-ids]
  (when (not-empty duplicate-ids)
    (g/->error node-id :build-targets :fatal nil (format "The following ids are not unique: %s" (string/join ", " duplicate-ids)))))

(defn- embedded-component-desc->dependencies [{:keys [id type data] :as _embedded-component-desc} ext->resource-type]
  (when-some [component-resource-type (ext->resource-type type)]
    (let [component-read-fn (:read-fn component-resource-type)
          component-dependencies-fn (:dependencies-fn component-resource-type)]
      (try
        (component-dependencies-fn
          (with-open [reader (StringReader. data)]
            (component-read-fn reader)))
        (catch Exception error
          (log/warn :msg (format "Couldn't determine dependencies for embedded component %s." id) :exception error)
          nil)))))

(defn make-game-object-dependencies-fn [workspace]
  ;; TODO: This should probably also consider resource property overrides?
  (let [default-dependencies-fn (resource-node/make-ddf-dependencies-fn GameObject$PrototypeDesc)]
    (fn [prototype-desc]
      (let [ext->resource-type (workspace/get-resource-type-map workspace)]
        (into (default-dependencies-fn prototype-desc)
              (mapcat #(embedded-component-desc->dependencies % ext->resource-type))
              (:embedded-components prototype-desc))))))

(defn embedded-component-instance-data [build-resource embedded-component-desc ^Matrix4d transform-matrix]
  {:pre [(workspace/build-resource? build-resource)
         (map? embedded-component-desc) ; EmbeddedComponentDesc in map format.
         (instance? Matrix4d transform-matrix)]}
  {:resource build-resource
   :transform transform-matrix
   :instance-msg embedded-component-desc})

(defn referenced-component-instance-data [build-resource component-desc ^Matrix4d transform-matrix proj-path->resource-property-build-target]
  {:pre [(workspace/build-resource? build-resource)
         (map? component-desc) ; ComponentDesc in map format, but PropertyDescs must have a :clj-value.
         (instance? Matrix4d transform-matrix)
         (ifn? proj-path->resource-property-build-target)]}
  (let [go-props-with-source-resources (:properties component-desc) ; Every PropertyDesc must have a :clj-value with actual Resource, etc.
        [go-props go-prop-dep-build-targets] (properties/build-target-go-props proj-path->resource-property-build-target go-props-with-source-resources)]
    {:resource build-resource
     :transform transform-matrix
     :property-deps go-prop-dep-build-targets
     :instance-msg (if (seq go-props)
                     (assoc component-desc :properties go-props)
                     component-desc)}))

(defn- build-game-object [build-resource dep-resources user-data]
  ;; Please refer to `/engine/gameobject/proto/gameobject/gameobject_ddf.proto`
  ;; when reading this. It will clear up how the output binaries are structured.
  ;; Be aware that these structures are also used to store the saved project
  ;; data. Sometimes a field will only be used by the editor *or* the runtime.
  ;; At this point, all referenced and embedded components will have emitted
  ;; BuildResources. The engine does not have a concept of an EmbeddedComponent.
  ;; They are written as separate binaries and referenced just like any other
  ;; ReferencedComponent. However, embedded components from different sources
  ;; might have been fused into one BuildResource if they had the same contents.
  ;; We must update any references to these BuildResources to instead point to
  ;; the resulting fused BuildResource. We also extract :instance-data from the
  ;; component build targets and embed these as ComponentDesc instances in the
  ;; PrototypeDesc that represents the game object.
  (let [build-go-props (partial properties/build-go-props dep-resources)
        instance-data (:instance-data user-data)
        component-msgs (map :instance-msg instance-data)
        component-go-props (map (comp build-go-props :properties) component-msgs)
        component-build-resource-paths (map (comp resource/proj-path dep-resources :resource) instance-data)
        component-descs (map (fn [component-msg fused-build-resource-path go-props]
                               (-> component-msg
                                   (dissoc :data :properties :type) ; Runtime uses :property-decls, not :properties
                                   (assoc :component fused-build-resource-path)
                                   (cond-> (not (contains? component-msg :scale))
                                           (assoc :scale default-scale-value)

                                           (seq go-props)
                                           (assoc :property-decls (properties/go-props->decls go-props false)))))
                             component-msgs
                             component-build-resource-paths
                             component-go-props)
        property-resource-paths (into (sorted-set)
                                      (comp cat (keep properties/try-get-go-prop-proj-path))
                                      component-go-props)
        prototype-desc {:components component-descs
                        :property-resources property-resource-paths}]
    {:resource build-resource
     :content (protobuf/map->bytes GameObject$PrototypeDesc prototype-desc)}))

(defn game-object-build-target [build-resource host-resource-node-id component-instance-datas component-build-targets]
  {:pre [(workspace/build-resource? build-resource)
         (g/node-id? host-resource-node-id)
         (vector? component-instance-datas)
         (vector? component-build-targets)]}
  ;; Extract the :instance-data from the component build targets so that
  ;; overrides can be embedded in the resulting game object binary. We also
  ;; establish dependencies to build-targets from any resources referenced
  ;; by script property overrides.
  (bt/with-content-hash
    {:node-id host-resource-node-id
     :resource build-resource
     :build-fn build-game-object
     :user-data {:instance-data component-instance-datas}
     :deps (into component-build-targets
                 (comp (mapcat :property-deps)
                       (util/distinct-by (comp resource/proj-path :resource)))
                 component-instance-datas)}))
