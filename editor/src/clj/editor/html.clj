;; Copyright 2020-2024 The Defold Foundation
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

(ns editor.html
  (:require [dynamo.graph :as g]
            [editor.code.resource :as r]
            [editor.code.lang.html :as html]
            [editor.workspace :as workspace]))

(def ^:private html-opts {:code {:grammar html/grammar}})

(g/defnode HtmlNode
  (inherits r/CodeEditorResourceNode)

  (output html g/Str (g/fnk [resource] (slurp resource :encoding "UTF-8"))))

(defn register-resource-types
  [workspace]
  (r/register-code-resource-type workspace
                                    :ext "html"
                                    :label "HTML"
                                    :textual? true
                                    :node-type HtmlNode
                                    :view-types [:html :code]
                                    :view-opts html-opts))

