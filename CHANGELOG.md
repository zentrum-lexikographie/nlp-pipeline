# Changelog

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
