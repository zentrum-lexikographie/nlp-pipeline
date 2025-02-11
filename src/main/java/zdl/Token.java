package zdl;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public record Token(Integer n, String form, String lemma, String upos, String xpos, Map<String, String> feats,
                    Integer head, String deprel, String deps, Map<String, String> misc) {
    private static final Pattern fieldSplit = Pattern.compile("\t| {2,}");
    private static final Pattern escapedUnderscore = Pattern.compile("__");

    public static Token fromCoNLL(String line) {
        Integer n = null;
        String form = null;
        String lemma = null;
        String upos = null;
        String xpos = null;
        Map<String, String> feats = null;
        Integer head = null;
        String deprel = null;
        String deps = null;
        Map<String, String> misc = null;
        var fields = fieldSplit.split(line);
        for (int i = 0; i < fields.length; i++) {
            var field = fields[i];
            if (field.equals("_")) {
                continue;
            }
            field = escapedUnderscore.matcher(field).replaceAll("_");
            switch (i) {
                case 0:
                    n = Integer.parseInt(field);
                    break;
                case 1:
                    form = field;
                    break;
                case 2:
                    lemma = field;
                    break;
                case 3:
                    upos = field;
                    break;
                case 4:
                    xpos = field;
                    break;
                case 5:
                    feats = readAttributes(field);
                    break;
                case 6:
                    head = Integer.parseInt(field);
                    break;
                case 7:
                    deprel = field;
                    break;
                case 8:
                    deps = field;
                    break;
                case 9:
                    misc = readAttributes(field);
                    break;
            }
        }
        return new Token(n, form, lemma, upos, xpos, feats, head, deprel, deps, misc);
    }

    private static final Pattern attrSplit = Pattern.compile("\\|");

    private static Map<String, String> readAttributes(String field) {
        final var attrs = new HashMap<String, String>();
        for (var attr : attrSplit.split(field)) {
            final var sepIndex = attr.indexOf('=');
            attrs.put(attr.substring(0, sepIndex), attr.substring(sepIndex + 1));
        }
        return attrs;
    }

    public boolean spaceAfter() {
        var misc = misc();
        return misc == null || misc.getOrDefault("SpaceAfter", "Yes").equals("Yes");
    }
}
