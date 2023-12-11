;; Copyright 2020-2023 The Defold Foundation
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

(ns editor.code.lang.html
  (:require [editor.code.data :as data])
  (:import [java.io PushbackReader]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:private attribute-interior
  [
   {:begin #"="
    :begin-captures {0 {:name "punctuation.separator.key-value.html"}}
    :end #"(?<=[^\\s=])(?!\\s*=)|(?=/?>)"
    :patterns [
               {:match #"([^\\s\"'=<>`/]|/(?!>))+"
                :name "string.unquoted.html"}
               {:begin #"\""
                :begin-captures {0 {:name "punctuation.definition.string.begin.html"}}
                :end #"\""
                :end-captures {0 {:name "punctuation.definition.string.end.html"}}
                :name "string.quoted.double.html"}
               {:begin #"'"
                :begin-captures {0 {:name "punctuation.definition.string.begin.html"}}
                :end #"'"
                :end-captures {0 {:name "punctuation.definition.string.end.html"}}
                :name "string.quoted.single.html"}
               {:match #"="
                :name "invalid.illegal.unexpected-equals-sign.html"}
    ]}
  ])

(def grammar
  {:name "HTML"
   :scope-name "source.html"
   ;; indent patterns shamelessly stolen from textmate:
   ;; https://github.com/textmate/html.tmbundle/blob/master/Preferences/Miscellaneous.plist
   :indent {
            :begin #"(?x)
		>(?!\?|(?:area|base|basefont|br|col|embed|frame|hr|html|img|input|link|meta|param|source|track|wbr)\b|[^>]*/>)
		  ([A-Za-z0-9]+)(?=\s|>)\b[^>]*>(?!.*>/\1>)
		|>!--(?!.*-->)
		|>\?php.+?\b(if|else(?:if)?|for(?:each)?|while)\b.*:(?!.*end\1)
		|\{[^}\"']*$"
            :end #"(?x)
		^\s*
		(>/(?!html)
		  [A-Za-z0-9]+\b[^>]*>
		|-->
		|>\?(php)?\s+(else(if)?|end(if|for(each)?|while))
		|\}
		)"
            }
   :patterns [
              {:match #"\b(?:true|false|null)\b"
               :name "constant.language.html"}
              {:match #"(?x)-?(?:0|[1-9]\d*)(?:\n(?:\n\.\d+)?(?:[eE][+-]?\d+)?)?"
               :name "constant.numeric.html"} 

              {:begin #"\""
               :begin-captures {0 {:name "punctuation.definition.string.begin.html"}}
               :end #"\""
               :end-captures {0 {:name "punctuation.definition.string.end.html"}}
               :name "string.quoted.double.html"} 

              {:begin #"/\*"
               :begin-captures {0 {:name "punctuation.definition.comment.begin.html"}}
               :end #"\*/"
               :end-captures {0 {:name "punctuation.definition.comment.end.html"}}
               :name "comment.quoted.double.html"} 
              
              {:begin #"<!--"
               :begin-captures {0 {:name "punctuation.definition.comment.begin.html"}}
               :end #"-->"
               :end-captures {0 {:name "punctuation.definition.comment.end.html"}}
               :name "comment.double.html"}
              
              {:match #"<[^>]*>"
               :name "tag.language.html"}
              
              {:begin #"(s(hape|cope|t(ep|art)|ize(s)?|p(ellcheck|an)|elected|lot|andbox|rc(set|doc|lang)?)|h(ttp-equiv|i(dden|gh)|e(ight|aders)|ref(lang)?)|n(o(nce|validate|module)|ame)|c(h(ecked|arset)|ite|o(nt(ent(editable)?|rols)|ords|l(s(pan)?|or))|lass|rossorigin)|t(ype(mustmatch)?|itle|a(rget|bindex)|ranslate)|i(s(map)?|n(tegrity|putmode)|tem(scope|type|id|prop|ref)|d)|op(timum|en)|d(i(sabled|r(name)?)|ownload|e(coding|f(er|ault))|at(etime|a)|raggable)|usemap|p(ing|oster|la(ysinline|ceholder)|attern|reload)|enctype|value|kind|for(m(novalidate|target|enctype|action|method)?)?|w(idth|rap)|l(ist|o(op|w)|a(ng|bel))|a(s(ync)?|c(ce(sskey|pt(-charset)?)|tion)|uto(c(omplete|apitalize)|play|focus)|l(t|low(usermedia|paymentrequest|fullscreen))|bbr)|r(ows(pan)?|e(versed|quired|ferrerpolicy|l|adonly))|m(in(length)?|u(ted|ltiple)|e(thod|dia)|a(nifest|x(length)?)))(?![\\w:-])"
               :begin-captures {0 {:name "punctuation.definition.keyword.begin.html"}}
               :end #"(?=\\s*+[^=\\s])"
               :end-captures {0 {:name "punctuation.definition.keyword.end.html"}}
               :patterns attribute-interior} 
               ]})

(defn lines->html [lines & options]
  (with-open [lines-reader (data/lines-reader lines)
              pushback-reader (PushbackReader. lines-reader)]))
