package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionEvaluatorTest {

    private ObjectMapper mapper;
    private ObjectNode item;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper();
        item = (ObjectNode) mapper.readTree("""
            {
              "pk": {"S": "item1"},
              "data": {"M": {
                "title":   {"S": "Hello"},
                "answer":  {"S": "World"},
                "sources": {"L": [{"M": {"id": {"S": "1"}}}, {"M": {"id": {"S": "2"}}}]}
              }}
            }
            """);
    }

    @Test
    void project_singleNestedPath_returnsOnlyThatPath() {
        ObjectNode result = ProjectionEvaluator.project(item, "pk, #d.title",
                mapper.createObjectNode().put("#d", "data"));

        assertEquals("item1", result.get("pk").get("S").asText());
        assertTrue(result.get("data").has("M"));
        assertEquals("Hello", result.get("data").get("M").get("title").get("S").asText());
        assertFalse(result.get("data").get("M").has("answer"));
        assertFalse(result.get("data").get("M").has("sources"));
    }

    @Test
    void project_multipleNestedPathsOnSameMap_returnsAllRequestedPaths() {
        ObjectNode result = ProjectionEvaluator.project(item, "pk, #d.title, #d.answer, #d.sources",
                mapper.createObjectNode().put("#d", "data"));

        assertEquals("item1", result.get("pk").get("S").asText());
        ObjectNode data = (ObjectNode) result.get("data").get("M");
        assertEquals("Hello", data.get("title").get("S").asText());
        assertEquals("World", data.get("answer").get("S").asText());
        assertTrue(data.get("sources").has("L"));
        assertEquals(2, data.get("sources").get("L").size());
    }

    @Test
    void project_multipleListIndicesOnSameList_returnsAllRequestedElements() {
        ObjectNode result = ProjectionEvaluator.project(item, "#d.sources[0], #d.sources[1]",
                mapper.createObjectNode().put("#d", "data"));

        ObjectNode data = (ObjectNode) result.get("data").get("M");
        assertTrue(data.get("sources").has("L"));
        assertEquals(2, data.get("sources").get("L").size(),
                "Both requested list indices must survive — sibling bug of multi-path map merge");
        assertEquals("1", data.get("sources").get("L").get(0).get("M").get("id").get("S").asText());
        assertEquals("2", data.get("sources").get("L").get(1).get("M").get("id").get("S").asText());
    }
}
