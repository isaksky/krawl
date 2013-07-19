(defproject krawl "0.1.0-SNAPSHOT"
  :description "Web crawler"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [clj-tagsoup "0.3.0"]
                 [clj-http "0.7.3"]
                 [org.clojure/core.match "0.2.0-rc2"]
                 [com.taoensso/timbre "2.1.2"]
                 ;;[com.cemerick/url "0.0.8"]
                 ]
  :java-source-paths ["src/java"])
