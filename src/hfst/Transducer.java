/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package hfst;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lookup on HFST-Optimized-Lookup (HFST-OL) transducers.
 *
 * @author Sam Hardwick <sam.hardwick@iki.fi>
 * @author Gregor Middell <gregor@middell.net>
 */
public class Transducer {
    private final static int MAX_OUTPUT_SYMBOLS = 1024;

    private final static int NO_SYMBOL = 65535;

    private final static long TRANSITION_START_INDEX = 2147483648L;

    private final static long NO_TARGET = 4294967295L;

    public static Transducer read(File file) throws IOException {
        final RandomAccessFile raf = new RandomAccessFile(file, "r");
        final ByteBuffer bb = raf.getChannel()
                .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                .order(ByteOrder.LITTLE_ENDIAN);

        if (!"HFST".equals(readString(bb))) {
            throw new IllegalArgumentException(
                    String.format("%s is not a HFST automaton", file)
            );
        }

        final int headerLength = readUnsignedShort(bb) + 1;
        bb.position(bb.position() + headerLength);

        readUnsignedShort(bb); // input count

        final int symbolCount = readUnsignedShort(bb);
        final int indexCount = (int) readUnsignedInt(bb);
        final int transitionCount = (int) readUnsignedInt(bb);

        bb.position(bb.position() + 44);

        final List<String> alphabet = new ArrayList<String>(symbolCount);
        for (int ai = 0; ai < symbolCount; ai++) {
            alphabet.add(readString(bb));
        }
        alphabet.set(0, ""); // epsilon

        final List<IndexEntry> indexEntries = new ArrayList<IndexEntry>(indexCount);
        for (int tii = 0; tii < indexCount; tii++) {
            indexEntries.add(new IndexEntry(
                    readUnsignedShort(bb),
                    readUnsignedInt(bb)
            ));
        }

        final List<Transition> transitions = new ArrayList<Transition>(transitionCount);
        for (int ti = 0; ti < transitionCount; ti++) {
            transitions.add(new Transition(
                    readUnsignedShort(bb),
                    readUnsignedShort(bb),
                    readUnsignedInt(bb)
            ));
        }

        return new Transducer(
                alphabet.toArray(new String[0]),
                indexEntries.toArray(new IndexEntry[0]),
                transitions.toArray(new Transition[0])
        );
    }

    private static String readString(ByteBuffer bb) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            while (true) {
                byte b = bb.get();
                if (b == 0) {
                    break;
                }
                buf.write(b);
            }
            return new String(buf.toByteArray(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static int readUnsignedShort(ByteBuffer bb) {
        return 0xffff & bb.getShort();
    }

    private static long readUnsignedInt(ByteBuffer bb) {
        return 0xffffffffL & bb.getInt();
    }

    private static class IndexEntry {

        private final int input;
        private final long target;

        public IndexEntry(int input, long target) {
            this.input = input;
            this.target = target;
        }

        public boolean isFinal() {
            return input == NO_SYMBOL && target != NO_TARGET;
        }
    }

    private static class Transition {

        private final int input;
        private final int output;
        private final long target;

        public Transition(int input, int output, long target) {
            this.input = input;
            this.output = output;
            this.target = target;
        }

        public boolean isFinal() {
            return input == NO_SYMBOL && output == NO_SYMBOL && target == 1;
        }
    }

    private static class Lookup {
        private final List<List<String>> results;
        private final int[] input;
        private final int[] output;
        private int inputIndex;
        private int outputIndex;

        private Lookup(int[] input) {
            this.input = input;
            this.inputIndex = 0;
            this.output = new int[MAX_OUTPUT_SYMBOLS];
            this.outputIndex = 0;
            for (int oi = 0; oi < this.output.length; oi++) {
                this.output[oi] = NO_SYMBOL;
            }
            this.results = new ArrayList<List<String>>();
        }

        private boolean isEndOfInput() {
            return this.inputIndex == this.input.length;
        }

        private int prevInput() {
            return this.input[this.inputIndex - 1];
        }

        private void addOutput(int output) {
            this.output[this.outputIndex++] = output;
        }

        private void addOutputEnd() {
            this.output[this.outputIndex] = NO_SYMBOL;
        }

        private void registerResult(String[] alphabet) {
            List<String> result = new ArrayList<String>();
            for (int oi = 0; this.output[oi] != NO_SYMBOL; oi++) {
                result.add(alphabet[this.output[oi]]);
            }
            this.results.add(result);
        }
    }

    private final String[] alphabet;
    private final Map<String, Integer> alphabetIndex;
    private final IndexEntry[] indexEntries;
    private final Transition[] transitions;

    private Transducer(String[] alphabet, IndexEntry[] indexEntries, Transition[] transitions) {
        this.alphabet = alphabet;
        this.alphabetIndex = new HashMap<String, Integer>();
        for (int ai = 0; ai < this.alphabet.length; ai++) {
            this.alphabetIndex.put(alphabet[ai], ai);
        }
        this.indexEntries = indexEntries;
        this.transitions = transitions;
    }

    private int pivot(long index) {
        if (index >= TRANSITION_START_INDEX) {
            index -= TRANSITION_START_INDEX;
        }
        return (int) index;
    }

    private void tryEpsilonIndices(Lookup lookup, int index) {
        final IndexEntry e = this.indexEntries[index];
        if (e.input == 0) {
            tryEpsilonTransitions(lookup, pivot(e.target));
        }
    }

    private void tryEpsilonTransitions(Lookup lookup, int index) {
        while (true) {
            final Transition t = transitions[index];
            if (t.input != 0) {
                break;
            }
            lookup.addOutput(t.output);
            doLookup(lookup, t.target);
            lookup.outputIndex--;
            ++index;
        }
    }

    private void findIndex(Lookup lookup, int index) {
        final int prevInput = lookup.prevInput();
        final IndexEntry candidate = indexEntries[index + prevInput];
        if (candidate.input == prevInput) {
            findTransitions(lookup, pivot(candidate.target));
        }
    }

    private void findTransitions(Lookup lookup, int index) {
        while (true) {
            final Transition t= transitions[index];
            if (t.input == NO_SYMBOL) {
                break;
            }
            if (t.input != lookup.prevInput()) {
                return;
            }
            lookup.addOutput(t.output);
            doLookup(lookup, t.target);
            lookup.outputIndex--;
            index++;
        }
    }

    private void doLookup(Lookup lookup, long target) {
        final boolean isTransition = target >= TRANSITION_START_INDEX;
        final int index = pivot(target);
        if (isTransition) {
            tryEpsilonTransitions(lookup, index + 1);
            if (lookup.isEndOfInput()) {
                lookup.addOutputEnd();
                if (transitions[index].isFinal()) {
                    lookup.registerResult(this.alphabet);
                }
                return;
            }
            lookup.inputIndex++;
            findTransitions(lookup, index + 1);
        } else {
            tryEpsilonIndices(lookup, index + 1);
            if (lookup.isEndOfInput()) {
                lookup.addOutputEnd();
                if (indexEntries[index].isFinal()) {
                    lookup.registerResult(this.alphabet);
                }
                return;
            }
            lookup.inputIndex++;
            findIndex(lookup, index + 1);
        }
        lookup.inputIndex--;
        lookup.addOutputEnd();
    }

    private int[] encode(String input) {
        final int[] symbols = new int[input.length()];
        for (int ii = 0; ii < symbols.length; ii++) {
            final Integer symbol = this.alphabetIndex.get(Character.toString(input.charAt(ii)));
            if (symbol == null) {
                throw new IllegalArgumentException(input);
            }
            symbols[ii] = symbol;
        }
        return symbols;
    }

    public List<List<String>> lookup(String input) {
        final Lookup lookup = new Lookup(encode(input));
        doLookup(lookup, 0);
        return lookup.results;
    }
}
