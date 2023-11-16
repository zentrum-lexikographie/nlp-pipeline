"""Segmentation, tokenization and language detection of texts."""
import sys
from pathlib import Path

import jpype
import jpype.imports


_korap_jar = Path(__file__) / '..' / 'KorAP-Tokenizer-2.2.2-standalone.jar'
jpype.startJVM(classpath=[_korap_jar.resolve().as_posix()])

# pylint: disable=import-error,wrong-import-position
from de.ids_mannheim.korap.tokenizer import DerekoDfaTokenizer_de

tokenizer = DerekoDfaTokenizer_de()

def tokenize(text):
    '''Segments and tokenizes the given string.'''
    words = []
    spaces = []
    sent_starts = []
    last_token_end = -1
    for sentence_span in tokenizer.sentPosDetect(text):
        sent_start = sentence_span.getStart()
        sent_end = sentence_span.getEnd()
        sent = text[sent_start:sent_end]
        for t_index, token_span in enumerate(tokenizer.tokenizePos(sent)):
            sent_starts.append(t_index == 0)
            token_start = sent_start + token_span.getStart()
            token_end = sent_start + token_span.getEnd()
            token = text[token_start:token_end]
            words.append(token)
            if last_token_end >= 0:
                spaces.append(last_token_end < token_start)
            last_token_end = token_end
    if last_token_end >= 0:
        spaces.append(False)
    return (words, spaces, sent_starts)


if __name__ == '__main__':
    print(repr(tokenize(' '.join(sys.argv[1:]))))
