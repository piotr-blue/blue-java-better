package blue;

import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Parent patches may replace an embedding but cannot cross into its independently owned body. */
@Tag("contracts")
class ContractManagedBoundaryTest {
  @ParameterizedTest
  @CsvSource({
    "/paidTotal,true",
    "/metadata/a~1b/count,true",
    "/children/a~1b,true",
    "/children/a~1b/count,false",
    "/children/a~0b/count,false",
    "/children/a~01b/count,false"
  })
  void checksStrictDecodedAncestorsWithoutConfusingEscapedKeys(String path, boolean allowed) {
    Object child =
        m(
            "count",
            0,
            "contracts",
            m("input", m("type", "Contracts/External Channel", "event", m("target", "child"))));
    boolean boundary = path.equals("/children/a~1b");
    Object replacement = boundary ? m("count", 42, "contracts", obj(child).get("contracts")) : 42;
    Object source =
        m(
            "paidTotal",
            0,
            "metadata",
            m("a/b", m("count", 0)),
            "children",
            m("a/b", child, "a~b", child, "a~1b", child),
            "contracts",
            m(
                "embedded",
                    m(
                        "type",
                        "Contracts/Process Embedded",
                        "collectionPaths",
                        List.of("/children")),
                "input", m("type", "Contracts/External Channel", "event", m("target", "parent")),
                "edit",
                    m(
                        "type",
                        "Contracts/Sequential Workflow",
                        "channel",
                        "input",
                        "steps",
                        List.of(
                            m(
                                "type",
                                "Contracts/Update Document",
                                "changeset",
                                List.of(m("op", "replace", "path", path, "val", replacement)))))));
    try (Contracts.Session session = Contracts.create().openJson(ContractWire.write(source))) {
      session.initialize().requireApplied();
      String before = session.identity();
      var result = session.process(Map.of("target", "parent"), 1);
      assertEquals(allowed, result.applied(), result.observations().toString());
      if (allowed) {
        assertEquals("42", session.read(boundary ? path + "/count" : path).toString());
      } else {
        assertEquals(before, session.identity());
        assertTrue(
            result
                .observations()
                .get("diagnostic")
                .toString()
                .contains("MANAGED_BOUNDARY_VIOLATION"));
      }
    }
  }
}
