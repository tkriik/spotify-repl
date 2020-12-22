(defproject spotify-repl "0.1.0"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [camel-snake-kebab "0.4.2"]
                 [cheshire "5.10.0"]
                 [com.cemerick/url "0.1.1"]
                 [com.taoensso/timbre "5.1.0"]
                 [http-kit "2.3.0"]]
  :repl-options {:init-ns spotify-repl.core})
