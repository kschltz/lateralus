(ns kschltz.agent.plugins.file-edit
  "file_edit tool plugin — adds the :file-edit tool for general
   (non-Clojure) file editing."
  (:require [kschltz.agent.tools.file-edit :as file-edit]))

(def plugin
  "Plugin: adds the :file-edit tool (read_file, write_file, edit_file,
   list_dir, show_diff). Hard-refuses .clj/.cljs/.cljc/.edn paths
   (use clj_edit for those)."
  {:plugin/name :file-edit
   :plugin/doc  "Adds the :file-edit tool for general (non-Clojure) file editing."
   :plugin/register
   (fn plugin-register [state _tool-defs]
     (update state :tools (fnil conj []) (file-edit/file-edit-tool)))})
