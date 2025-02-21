package zdl;

import mtas.analysis.token.MtasTokenString;
import mtas.codec.payload.MtasPayloadEncoder;
import mtas.codec.util.CodecInfo;
import mtas.parser.cql.MtasCQLParser;
import mtas.parser.cql.ParseException;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.spans.SpanWeight;
import org.apache.lucene.search.spans.Spans;
import org.apache.lucene.store.FSDirectory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.*;

import static mtas.codec.payload.MtasPayloadEncoder.ENCODE_PARENT;
import static mtas.codec.payload.MtasPayloadEncoder.ENCODE_PAYLOAD;

public class Index {
    public static final String ANNOTATIONS_FIELD = "annotations";
    private final FSDirectory directory;

    public Index(FSDirectory directory) {
        this.directory = directory;
    }

    public int query(String filter, String cql) throws ParseException, IOException {
        var filterQuery = new MatchAllDocsQuery();
        var cqlQueryParser = new MtasCQLParser(new BufferedReader(new StringReader(cql)));
        var cqlQuery = cqlQueryParser.parse(ANNOTATIONS_FIELD, "l", null, null, null);
        try (var reader = DirectoryReader.open(directory)) {
            var searcher = new IndexSearcher(reader, ForkJoinPool.commonPool());
            var cqlWeight = (SpanWeight) searcher.rewrite(cqlQuery).createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 0.0f);
            return searcher.search(filterQuery, new CollectorManager<>() {
                private int hits = 0;

                @Override
                public Collector newCollector() throws IOException {
                    return new PositiveScoresOnlyCollector(new Collector() {
                        @Override
                        public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
                            var reader = context.reader();
                            var codecInfo = CodecInfo.getCodecInfoFromTerms(reader.terms(ANNOTATIONS_FIELD));
                            return new LeafCollector() {
                                @Override
                                public void collect(int doc) throws IOException {
                                    var spans = cqlWeight.getSpans(context, SpanWeight.Postings.POSITIONS);
                                    if (spans == null || spans.advance(doc) != doc) {
                                        return;
                                    }
                                    while (true) {
                                        var start = spans.nextStartPosition();
                                        if (start == Spans.NO_MORE_POSITIONS) {
                                            break;
                                        }
                                        var end = spans.endPosition();
                                        var document = reader.document(doc);
                                        var id = document.get("id");
                                        for (var sentence : codecInfo.getPrefixFilteredObjectsByPositions(ANNOTATIONS_FIELD, doc, List.of("s"), start, end)) {
                                            var sentenceStart = sentence.getPositionStart();
                                            var sentenceEnd = sentence.getPositionEnd();
                                            codecInfo.getObjectsByPositions(ANNOTATIONS_FIELD, doc, sentenceStart, sentenceEnd);
                                        }
                                        hits++;
                                    }
                                }

                                @Override
                                public void setScorer(Scorable scorer) {
                                }

                            };
                        }

                        @Override
                        public ScoreMode scoreMode() {
                            return ScoreMode.COMPLETE_NO_SCORES;
                        }
                    });
                }

                @Override
                public Integer reduce(Collection<Collector> collectors) {
                    return hits;
                }
            });
        }
    }
    public int query(String cql) throws ParseException, IOException {
        var queryParser = new MtasCQLParser(new BufferedReader(new StringReader(cql)));
        var query = queryParser.parse(ANNOTATIONS_FIELD, "l", null, null, null);
        int hits = 0;
        try (var reader = DirectoryReader.open(directory)) {
            var searcher = new IndexSearcher(reader, ForkJoinPool.commonPool());
            var weight = (SpanWeight) searcher.rewrite(query).createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 0.0f);
            for (var segment : reader.leaves()) {
                var spans = weight.getSpans(segment, SpanWeight.Postings.POSITIONS);
                if (spans == null) {
                    continue;
                }
                var segmentReader = segment.reader();
                var liveDocs = segmentReader.getLiveDocs();
                var ci = CodecInfo.getCodecInfoFromTerms(segmentReader.terms(ANNOTATIONS_FIELD));
                while (spans.nextDoc() != Spans.NO_MORE_DOCS) {
                    var doc = spans.docID();
                    if (liveDocs != null && !liveDocs.get(doc)) {
                        continue;
                    }
                    while (true) {
                        var start = spans.nextStartPosition();
                        if (start == Spans.NO_MORE_POSITIONS) {
                            break;
                        }
                        var end = spans.endPosition();
                        var document = segmentReader.document(doc);
                        var id = document.get("id");
                        for (var sentence : ci.getPrefixFilteredObjectsByPositions(ANNOTATIONS_FIELD, doc, List.of("s"), start, end)) {
                            var sentenceStart = sentence.getPositionStart();
                            var sentenceEnd = sentence.getPositionEnd();
                            ci.getObjectsByPositions(ANNOTATIONS_FIELD, doc, sentenceStart, sentenceEnd);
                        }
                        hits++;
                    }

                }
            }
            return hits;
        }
    }

    public void add(Iterator<Document> documents) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setCodec(Codec.forName("MtasCodec"));
        config.setUseCompoundFile(false);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            var pool = ForkJoinPool.commonPool();
            var results = new ArrayList<ForkJoinTask<Void>>();
            while (documents.hasNext()) {
                final var document = documents.next();
                final var result = pool.submit((Callable<Void>) () -> {
                    var indexDocument = new org.apache.lucene.document.Document();
                    indexDocument.add(new TextField("id", document.id(), Field.Store.YES));
                    indexDocument.add(new Field(ANNOTATIONS_FIELD, new AnnotationTokenStream(document), ANNOTATION_FIELD_TYPE));
                    writer.addDocument(indexDocument);
                    return null;
                });
                results.add(result);
            }
            results.forEach(ForkJoinTask::join);
        }
    }

    private static void addSentenceAnnotation(Map<Integer, List<MtasTokenString>> annotations, int position, MtasTokenString annotation) {
        annotations.computeIfAbsent(position, (p) -> new ArrayList<>()).add(annotation);
    }

    private static List<MtasTokenString> annotations(Document document) {
        var annotations = new ArrayList<MtasTokenString>();
        int id = 0;
        int position = 1;
        int docId = id;
        int docStart = position;
        var docAnnotation = new MtasTokenString(docId, "doc", "");
        annotations.add(docAnnotation);
        for (var paragraph : document.paragraphs()) {
            var paragraphId = ++id;
            int paragraphStart = position;
            var paragraphAnnotation = new MtasTokenString(paragraphId,"p", "");
            annotations.add(paragraphAnnotation);
            paragraphAnnotation.setParentId(docId);
            for (var sentence : paragraph.sentences()) {
                var sentenceId = ++id;
                int sentenceStart = position;
                var sentenceAnnotation = new MtasTokenString(sentenceId,"s", "");
                annotations.add(sentenceAnnotation);
                sentenceAnnotation.setParentId(paragraphId);
                var sentenceAnnotations = new HashMap<Integer, List<MtasTokenString>>();
                var tokens = sentence.tokens();
                for (int ti = 0, tl = tokens.length; ti < tl; ti++) {
                    var token = tokens[ti];
                    var head = token.head();
                    var dep = token.deprel();
                    if (dep != null && head != null && head > 0) {
                        var headPosition = sentenceStart + head - 1;
                        var childPosition = sentenceStart + ti;
                        var depPosition = Math.min(childPosition, headPosition);
                        var depAnnotationId = ++id;
                        var depAnnotation = new MtasTokenString(depAnnotationId, "dep", dep);
                        depAnnotation.setParentId(sentenceId);
                        depAnnotation.addPosition(childPosition);
                        depAnnotation.addPosition(headPosition);
                        addSentenceAnnotation(sentenceAnnotations, depPosition, depAnnotation);
                        var headAnnotation = new MtasTokenString(++id, "dep.hd", dep);
                        headAnnotation.setParentId(depAnnotationId);
                        headAnnotation.addPosition(headPosition);
                        addSentenceAnnotation(sentenceAnnotations, headPosition, headAnnotation);
                        var childAnnotation = new MtasTokenString(++id, "dep.cd", dep);
                        childAnnotation.setParentId(depAnnotationId);
                        childAnnotation.addPosition(childPosition);
                        addSentenceAnnotation(sentenceAnnotations, childPosition, childAnnotation);
                    }
                }
                var entities = sentence.entities();
                if (entities != null) {
                    for (var entity : entities) {
                        var entityAnnotation = new MtasTokenString(++id, "ent", entity.label());
                        entityAnnotation.setParentId(sentenceId);
                        for (int entityPosition : entity.positions()) {
                            entityAnnotation.addPosition(sentenceStart + entityPosition - 1);
                        }
                        var entityPosition = entityAnnotation.getPositionStart();
                        addSentenceAnnotation(sentenceAnnotations, entityPosition, entityAnnotation);
                    }
                }
                var collocations = sentence.collocations();
                if (collocations != null) {
                    for (var collocation : collocations) {
                        int collocationId = ++id;
                        var collocationAnnotation = new MtasTokenString(collocationId, "col", collocation.label());
                        collocationAnnotation.setParentId(sentenceId);
                        for (var collocatePosition : collocation.positions()) {
                            collocatePosition += sentenceStart - 1;
                            collocationAnnotation.addPosition(collocatePosition);
                            var collocateAnnotation = new MtasTokenString(++id, "col", collocation.label());
                            collocateAnnotation.setParentId(collocationId);
                            collocateAnnotation.addPosition(collocatePosition);
                            addSentenceAnnotation(sentenceAnnotations, collocatePosition, collocateAnnotation);
                        }
                        var collocationPosition = collocationAnnotation.getPositionStart();
                        addSentenceAnnotation(sentenceAnnotations, collocationPosition, collocationAnnotation);
                    }
                }
                for (var token : tokens) {
                    var tokenAnnotations = new ArrayList<MtasTokenString>();
                    tokenAnnotations.add(new MtasTokenString(++id,"t", token.form()));
                    if (!token.spaceAfter()) {
                        tokenAnnotations.add(new MtasTokenString(++id,"ws", "0"));
                    }
                    if (token.lemma() != null) tokenAnnotations.add(new MtasTokenString(++id,"l", token.lemma()));
                    if (token.upos() != null) tokenAnnotations.add(new MtasTokenString(++id,"p", token.upos()));
                    if (token.xpos() != null) tokenAnnotations.add(new MtasTokenString(++id,"pos", token.xpos()));
                    if (token.feats() != null) {
                        for (var feat : token.feats().entrySet()) {
                            tokenAnnotations.add(new MtasTokenString(++id, feat.getKey().toLowerCase(), feat.getValue()));
                        }
                    }
                    for (var tokenAnnotation : tokenAnnotations) {
                        tokenAnnotation.addPosition(position);
                        tokenAnnotation.setParentId(sentenceId);
                    }
                    annotations.addAll(sentenceAnnotations.getOrDefault(position, Collections.emptyList()));
                    annotations.addAll(tokenAnnotations);
                    position++;
                }
                sentenceAnnotation.addPositionRange(sentenceStart, position);
            }
            paragraphAnnotation.addPositionRange(paragraphStart, position);
        }
        docAnnotation.addPositionRange(docStart, position);
        return annotations;
    }

    private static class AnnotationTokenStream extends TokenStream {
        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
        private final PayloadAttribute payloadAtt = addAttribute(PayloadAttribute.class);
        private final PositionIncrementAttribute positionIncrementAtt = addAttribute(PositionIncrementAttribute.class);
        private final List<MtasTokenString> annotations;
        private Iterator<MtasTokenString> it;
        private int currentPosition;

        public AnnotationTokenStream(Document document) {
            this.annotations = annotations(document);
            reset();
        }

        @Override
        public boolean incrementToken() throws IOException {
            if (!it.hasNext()) {
                return false;
            }
            var token = it.next();
            var positionIncrement = token.getPositionStart() - currentPosition;
            currentPosition = token.getPositionStart();
            termAtt.setEmpty();
            termAtt.append(token.getValue());
            positionIncrementAtt.setPositionIncrement(positionIncrement);
            payloadAtt.setPayload(new MtasPayloadEncoder(token, ENCODE_PAYLOAD | ENCODE_PARENT).getPayload());
            return true;
        }

        @Override
        public void reset() {
            currentPosition = -1;
            it = this.annotations.iterator();
        }
    }
    private static final FieldType ANNOTATION_FIELD_TYPE = new FieldType();

    static {
        ANNOTATION_FIELD_TYPE.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        ANNOTATION_FIELD_TYPE.setTokenized(true);
        ANNOTATION_FIELD_TYPE.freeze();
    }
}
