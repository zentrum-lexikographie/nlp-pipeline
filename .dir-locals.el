;;; Directory Local Variables
;;; For more information see (info "(emacs) Directory Variables")

((python-mode . ((python-test-runner pytest)
                 (pyvenv-workon "nlp")))
 (clojure-mode . ((cider-preferred-build-tool . clojure-cli)
                  (cider-clojure-cli-aliases . ":dev:test:clerk"))))
