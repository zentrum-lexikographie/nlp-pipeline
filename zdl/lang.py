"""Language detection"""

from lingua import Language, LanguageDetectorBuilder

_languages = [
    Language.GERMAN,
    Language.ENGLISH,
    Language.FRENCH,
    Language.LATIN,
    Language.GREEK
]

_detector = LanguageDetectorBuilder\
    .from_languages(*_languages)\
    .with_preloaded_language_models()\
    .with_low_accuracy_mode()\
    .build()

def detect(text):
    """Detects the language (ISO code) of a given text."""
    lang = _detector.detect_language_of(text)
    return lang.iso_code_639_3.name.lower() if lang else None
