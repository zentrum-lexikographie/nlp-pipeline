package zdl;

import mtas.parser.cql.ParseException;
import org.apache.lucene.store.FSDirectory;
import org.junit.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.StreamSupport;

import static java.util.Objects.*;

public class IndexTest {

    private Path indexPath;
    private Index index;

    @Before
    public void initIndex() throws IOException {
        indexPath = Files.createTempDirectory("zdl-korap-index.");
        index = new Index(FSDirectory.open(indexPath));
        index.add(sampleDocuments().iterator());
    }

    @After
    public void removeIndex() throws IOException {
        index = null;
        try (var indexFiles = Files.walk(indexPath)) {
            indexFiles.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private List<Document> sampleDocuments() throws IOException {
        final InputStream stream = requireNonNull(getClass().getResourceAsStream("/polspeech.anno.conll"));
        try (var reader = new BufferedReader(new InputStreamReader(stream))) {
            var documents = new ArrayList<Document>();
            var it = Document.fromCoNLL(reader);
            while (it.hasNext()) documents.add(it.next());
            return documents;
        }
    }

    @Test
    public void testQuery() throws IOException, ParseException {
        var start = System.currentTimeMillis();
        int hits = index.query("", "[p=\"ADJ\"] within <col/>");
        System.out.printf("%,d hits in %,d ms\n", hits, System.currentTimeMillis() - start);
    }
}
