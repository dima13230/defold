(ns dynamo.project
  (:require [clojure.java.io :as io]
            [plumbing.core :refer [defnk]]
            [schema.core :as s]
            [dynamo.system :as ds]
            [dynamo.file :as file]
            [dynamo.node :as n :refer [defnode Scope]]
            [dynamo.types :as t]
            [dynamo.ui :as ui]
            [internal.clojure :as clojure]
            [internal.query :as iq]
            [internal.system :as is]
            [eclipse.resources :as resources]
            [service.log :as log])
  (:import [org.eclipse.core.resources IFile IProject IResource]
           [org.eclipse.ui PlatformUI IEditorSite ISelectionListener]
           [org.eclipse.ui.internal.registry FileEditorMapping EditorRegistry]
           [org.eclipse.jface.viewers ISelection ISelectionProvider]))

(set! *warn-on-reflection* true)

(defn register-filetype
  [extension default?]
  (ui/swt-safe
    (let [reg ^EditorRegistry (.getEditorRegistry (PlatformUI/getWorkbench))
          desc (.findEditor reg "com.dynamo.cr.sceneed2.scene-editor")
          mapping (doto
                    (FileEditorMapping. extension)
                    (.addEditor desc))
          all-mappings (.getFileEditorMappings reg)]
      (when default? (.setDefaultEditor mapping desc))
      (.setFileEditorMappings reg (into-array (concat (seq all-mappings) [mapping]))))))

(defn- handle
  [key filetype handler]
  (ds/update-property (ds/current-scope) :handlers assoc-in [key filetype] handler))

(defn register-loader
  [filetype loader]
  (handle :loader filetype loader))

(defn register-editor
  [filetype editor-builder]
  (handle :editor filetype editor-builder)
  (register-filetype filetype true))

(def default-handlers {:loader {"clj" clojure/on-load-code}})

(def no-such-handler
  (memoize
    (fn [key ext]
      (fn [& _] (throw (ex-info (str "No " (name key) " has been registered that can handle " ext) {}))))))

(defn- handler
  [project-scope key ext]
  (or
    (get-in project-scope [:handlers key ext])
    (no-such-handler key ext)))

(defn- loader-for [project-scope ext] (handler project-scope :loader ext))
(defn- editor-for [project-scope ext] (handler project-scope :editor ext))

(defn node?! [n kind]
  (assert (satisfies? t/Node n) (str kind " functions must return a node. Received " (type n) "."))
  n)

(defn load-resource
  [project-node path]
  (ds/transactional
    (ds/in project-node
      (let [loader        (loader-for project-node (file/extension path))
            resource-node (loader path (io/reader path))]
        (node?! resource-node "Loader")
        resource-node))))

(defn node-by-filename
  [project-node path factory]
  (if-let [node (first (iq/query (:world-ref project-node) [[:filename path]]))]
    node
    (factory project-node path)))

(defnk produce-selection [this selected-nodes]
  (let [node-ids (mapv :_id selected-nodes)]
    ;; TODO - notify listeners
    (reify
      ISelection
      (isEmpty [this] (empty? node-ids))
      clojure.lang.IDeref
      (deref [this] node-ids))))

(defnode Selection
  (input selected-nodes [t/Node])

  (output selection s/Any :cached :on-update produce-selection)

  ISelectionListener
  (selectionChanged [this part selection]
    (prn "*** selectionChanged happened! ***" part selection))

  ISelectionProvider
  (getSelection ^ISelection [this]
    (n/get-node-value this :selection))
  (setSelection [this selection]
    (prn "*** setSelection not implemented ***"))
  (addSelectionChangedListener [this listener]
    (prn "*** addSelectionChangedListener not implemented ***"))
  (removeSelectionChangedListener [this listener]
    (prn "*** removeSelectionChangedListener not implemented ***")))

(defnode CannedProperties
  (property rotation s/Str    (default "twenty degrees starboard"))
  (property translation s/Str (default "Guten abend.")))

(defn- build-editor-node
  [project-node path site]
  (let [content-root   (node-by-filename project-node path load-resource)
        editor-factory (editor-for project-node (file/extension path))]
    (node?! (editor-factory project-node site content-root) "Editor")))

(defn- build-selection-node
  [editor-node]
  (ds/in editor-node
    (let [selection-node  (ds/add (make-selection))
          properties-node (ds/add (make-canned-properties :rotation "e to the i pi"))]
      (ds/connect properties-node :self selection-node :selected-nodes)
      selection-node)))

(defn make-editor
  [project-node path ^IEditorSite site]
  (let [[editor-node selection-node] (ds/transactional
                                       (ds/in project-node
                                         (let [editor-node    (build-editor-node project-node path site)
                                               selection-node (build-selection-node editor-node)]
                                           [editor-node selection-node])))]
    (.setSelectionProvider site selection-node)
    editor-node))

(defn- send-project-scope-message
  [graph self txn]
  (doseq [n (:nodes-added txn)]
    (ds/send-after n {:type :project-scope :scope self})))

; ---------------------------------------------------------------------------
; Lifecycle, Called by Eclipse
; ---------------------------------------------------------------------------
(defnode Project
  (inherits Scope)

  (property triggers        t/Triggers (default [#'n/inject-new-nodes #'send-project-scope-message]))
  (property tag             s/Keyword (default :project))
  (property eclipse-project IProject)
  (property branch          s/Str)

  (on :destroy
    (ds/delete self)))

(defn load-project-and-tools
  [eclipse-project branch]
  (ds/transactional
    (let [project-node    (ds/add (make-project :eclipse-project eclipse-project :branch branch :handlers default-handlers))
          clojure-sources (filter clojure/clojure-source? (resources/resource-seq eclipse-project))]
      (ds/in project-node
        (doseq [source clojure-sources]
          (load-resource project-node (file/project-path project-node source))))
      project-node)))

(defn open-project
  [eclipse-project branch]
  (resources/listen-for-close (load-project-and-tools eclipse-project branch)))

; ---------------------------------------------------------------------------
; Documentation
; ---------------------------------------------------------------------------
(doseq [[v doc]
       {*ns*
        "Functions for performing transactional changes to a project and inspecting its current state."

        #'open-project
        "Called from com.dynamo.cr.editor.Activator when opening a project. You should not call this function."

        #'register-filetype
        "TODO"

        #'register-loader
        "Associate a filetype (extension) with a loader function. The given loader will be
used any time a file with that type is opened."

        #'register-editor
        "TODO"

        #'load-resource
        "Load a resource, usually from file. This looks up a suitable loader based on the filename.
Loaders must be registered via register-loader before they can be used.

This will invoke the loader function with the filename and a reader to supply the file contents."

        #'node-by-filename
        "TODO"

        #'Project
        "TODO"
}]
  (alter-meta! v assoc :doc doc))
