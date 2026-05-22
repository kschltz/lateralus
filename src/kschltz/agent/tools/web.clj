(ns kschltz.agent.tools.web
  "Web search tool — DuckDuckGo HTML search via builtin tool protocol.

   Returns [title url snippet] vectors, parsed as EDN by the existing
   parse :builtin multimethod.")
  (:require
   [clojure.string :as str]
   [hato.client :as hato]))
