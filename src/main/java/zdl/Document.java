package zdl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Models documents given in CoNLL-U format.
 */
public record Document (Paragraph[] paragraphs) {

    public Map<String,String> metadata() {
        for (var paragraph : paragraphs()) {
            for (var sentence : paragraph.sentences()) {
                return sentence.metadata();
            }
        }
        throw new IllegalStateException(toString());
    }

    public String id() {
        return metadata().get("newdoc id");
    }

    public static Iterator<Document> fromCoNLL(BufferedReader reader) {
        var sentences = Sentence.fromConLL(reader);
        return new Iterator<>() {
            private Document next;
            private List<Paragraph> paragraphs = new ArrayList<>();
            private List<Sentence> paragraph = new ArrayList<>();

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }
                while (sentences.hasNext()) {
                    var sentence = sentences.next();
                    if (sentence.startsDocument()) {
                        if (!paragraph.isEmpty()) {
                            paragraphs.add(new Paragraph(paragraph.toArray(new Sentence[paragraph.size()])));
                            paragraph.clear();
                        }
                        if (!paragraphs.isEmpty()) {
                            next = new Document(paragraphs.toArray(new Paragraph[paragraphs.size()]));
                            paragraphs.clear();
                        }
                        paragraph.add(sentence);
                        if (next != null) {
                            return true;
                        }
                    } else if (sentence.startsParagraph()) {
                        if (!paragraph.isEmpty()) {
                            paragraphs.add(new Paragraph(paragraph.toArray(new Sentence[paragraph.size()])));
                            paragraph.clear();
                        }
                        paragraph.add(sentence);
                    } else {
                        paragraph.add(sentence);
                    }
                }
                if (!paragraph.isEmpty()) {
                    paragraphs.add(new Paragraph(paragraph.toArray(new Sentence[paragraph.size()])));
                    paragraph.clear();
                }
                if (!paragraphs.isEmpty()) {
                    next = new Document(paragraphs.toArray(new Paragraph[paragraphs.size()]));
                    paragraphs.clear();
                }
                return next != null;
            }

            @Override
            public Document next() {
                var next = this.next;
                this.next = null;
                return next;
            }
        };
    }
}
