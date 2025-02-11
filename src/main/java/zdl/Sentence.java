package zdl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

public record Sentence(Map<String, String> metadata, Token[] tokens) {

    public boolean startsDocument() {
        return metadata.containsKey("newdoc id");
    }

    public boolean startsParagraph() {
        return metadata.containsKey("newpar id");
    }

    public Span[] entities() {
        return metadata.containsKey("entities") ? Span.fromJson(metadata.get("entities")) : null;
    }

    public Span[] collocations() {
        return metadata.containsKey("collocations") ? Span.fromJson(metadata.get("collocations")) : null;
    }

    public static Sentence fromCoNLL(List<String> lines) {
        var metadata = new HashMap<String, String>();
        var tokens = new ArrayList<Token>();
        for (var line : lines) {
            if (line.startsWith("#")) {
                line = line.substring(1);
                var sepIndex = line.indexOf('=');
                if (sepIndex < 1) {
                    throw new IllegalArgumentException(line);
                }
                var key = line.substring(0, sepIndex).trim();
                var value = line.substring(sepIndex + 1).trim();
                metadata.put(key, value);
            } else {
                tokens.add(Token.fromCoNLL(line));
            }
        }
        return new Sentence(metadata, tokens.toArray(new Token[tokens.size()]));
    }

    public static Iterator<Sentence> fromConLL(BufferedReader reader) {
        return new Iterator<>() {
            private final List<String> contents = new ArrayList<>();
            private Sentence next;

            @Override
            public boolean hasNext() {
                try {
                    if (next != null) {
                        return true;
                    }
                    for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                        if (line.isEmpty()) {
                            if (!contents.isEmpty()) {
                                next = Sentence.fromCoNLL(contents);
                                contents.clear();
                                break;
                            }
                        } else {
                            contents.add(line);
                        }
                    }
                    if (next == null && !contents.isEmpty()) {
                        next = Sentence.fromCoNLL(contents);
                    }
                    return next != null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public Sentence next() {
                var next = this.next;
                this.next = null;
                return next;
            }
        };
    }


}
