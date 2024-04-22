;;; Directory Local Variables
;;; For more information see (info "(emacs) Directory Variables")

((python-mode
  (python-test-runner pytest))
 (clojure-mode . ((cider-preferred-build-tool . clojure-cli)
                  (cider-clojure-cli-aliases . ":build:test:clerk:rngom:hfst:python:cli:annotate:vis"))))
