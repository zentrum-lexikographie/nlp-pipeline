package zdl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;

public record Span(String label, int[] positions) {

    public static Span[] fromJson(String json) {
        try {
            var spans = new ArrayList<Span>();
            for (var spanNode : objectMapper.readTree(json)) {
                var label = spanNode.get(0).asText();
                int[] positions = new int[spanNode.size() - 1];
                for (int pi = 0, pl = positions.length; pi < pl; pi++) {
                    positions[pi] = spanNode.get(pi + 1).asInt();
                }
                spans.add(new Span(label, positions));
            }
            return spans.toArray(new Span[spans.size()]);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    private final static ObjectMapper objectMapper = new ObjectMapper();
}
