(ns user) ; Under :dev alias, automatically load 'dev so the REPL is ready to go with zero interaction

(print "Starting... ") (flush)
(require 'dev)
(println "Ready.")