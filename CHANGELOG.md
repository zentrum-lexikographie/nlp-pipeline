# Changelog

## [1.1.0](https://github.com/zentrum-lexikographie/nlp-pipeline/compare/v1.0.1...v1.1.0) (2025-10-09)


### Features

* Add DDC client ([945c9a6](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/945c9a626d4695168bd0dd2556335079e266e121))
* Add KorAP-based corpora (DeReKo and DeLiKo) ([7b4ce5c](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/7b4ce5cb023d02b44756ad74f3ad13a8266e353b))
* Add WiC sentence embeddings for semantic clustering ([943429e](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/943429eb58e2b12c54e36f1793cf1024d1ea819c))
* Asynchronously query corpora for annotated example sentences ([33e79ec](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/33e79ec9480844145c87e597c10f3b3a8f607b2f))
* Build Docker images as part of CI/GA workflow ([7380e7d](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/7380e7d4faa9b7b536b8c381deede6f0b2b9b366))
* containerized version ([78e527e](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/78e527efab9b3e6fbe83a330d0b8934c75d5fda8))
* Convert TEI/XML sources to CoNLL-U ([2f9133c](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/2f9133c6cb237292f347e786d8ce1d26bbafba33))
* encrypted asynchronous messaging via RabbitMQ and AMQP/TLS ([7562d3d](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/7562d3db05442669376909ad5a8de542798343a6))
* on-the-fly annotation and deduplication of DDC results ([9283bca](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/9283bca92804abb71fe81a9db15f5f8671e151a3))

## [1.0.1](https://github.com/zentrum-lexikographie/nlp-pipeline/compare/v1.0.0...v1.0.1) (2025-07-03)


### Bug Fixes

* Do not lock dependencies, so platform-dependent installs are possible ([43963f4](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/43963f4752bb129850e7264bf92f7d1a9cffb4c5))


### Dependencies

* Prepare release v1.0.1 ([d1dc95f](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/d1dc95f152791a712a49fb85995f75316e0a0846))

## [1.0.0](https://github.com/zentrum-lexikographie/nlp-pipeline/compare/v0.1.0...v1.0.0) (2025-06-27)


### Miscellaneous Chores

* Add DOI badge ([56eb214](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/56eb2147b5039803b978e34dd37524d3bd74e1c8))

## 0.1.0 (2025-06-27)


### ⚠ BREAKING CHANGES

* Turn playground into Python library

### Features

* Add segmentation via SoMaJo as separate tool ([2433df1](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/2433df11840697c7ed5c10e4e56aec1c522fe599))
* **Annotate:** Add GDEX sentence scoring ([4e6d402](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/4e6d40204f29f9cb86e6c3c0d610cce94fe5af06))
* **Annotate:** Make pipeline component set configurable ([5d2d7e7](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/5d2d7e7e0f07191b0bb089b791e029d9d6e3922f))
* Turn playground into Python library ([201f5ac](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/201f5ac6de250362b3c0d4e666792949f83eb5d4))


### Bug Fixes

* **Collocation Extraction:** Dependent tokens are iterated over multiple times during SUBJA detection ([25363cb](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/25363cbe399b106c85b565c1c51e0355a3565c9a))
* **Collocation Extraction:** incorrect classification as 'OBJO' if token has 'Acc' case marking ([c0fa871](https://github.com/zentrum-lexikographie/nlp-pipeline/commit/c0fa871eb548c7f445639df26cbf789c6d3936a5))
