## Download DWDSmor/TAGH automaton

    mkdir -p resources/dwdsmor &&\
        curl -n -o resources/dwdsmor/dwdsmor.a\
            https://odo.dwds.de/~nolda/dwdsmor-tagh/lib/dwdsmor.a &&\
        clojure -T:build transpile-dwdsmor-automaton

## Bibliography

* Elisabeth Eder, Ulrike Krieg-Holz, and Udo Hahn. 2019. At the Lower
  End of Language—Exploring the Vulgar and Obscene Side of German. In
  Proceedings of the Third Workshop on Abusive Language Online, pages
  119–128, Florence, Italy. Association for Computational
  Linguistics. [Link](https://aclanthology.org/W19-3513)

## Acknowledgments

The GDEX implementation makes use of
[VulGer](https://aclanthology.org/W19-3513), a lexicon covering words
from the lower end of the German language register — terms typically
considered rough, vulgar, or obscene. VulGer is used under the terms
of the CC-BY-SA license.
