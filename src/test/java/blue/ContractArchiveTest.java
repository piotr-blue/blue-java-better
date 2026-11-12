package blue;

import static blue.ContractModel.m;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("contracts")
class ContractArchiveTest {
  @Test
  void restrictedArchiveSharesFrozenRecordsButReturnsIndependentWorkingState() {
    ContractProcessor processor = new ContractProcessor();
    var initial = processor.create(m("count", 1));
    var archive = processor.archive;
    String id = initial.identity(), root = initial.blueId();
    var restricted = archive.restrict(Map.of(root, id));
    assertSame(archive.versions.get(id), restricted.versions.get(id));
    var selected = restricted.get(root);
    selected.documents.put(
        selected.root,
        selected.documents.get(selected.root).withBlueId("changed-only-in-the-copy"));
    selected.edges.clear();
    assertEquals(root, archive.get(root).documents.get(selected.root).blueId);
    restricted.choices.clear();
    assertThrows(UnsupportedOperationException.class, () -> restricted.roots.get(root).clear());
    restricted.roots.remove(root);
    assertEquals(id, archive.bindings().get(root));
  }

  @Test
  void forkedSelectionsAndNewPublicationsCannotChangeTheOriginalArchive() {
    ContractProcessor processor = new ContractProcessor();
    var first = processor.create(m("count", 1));
    ContractGraph.Archive fork = new ContractGraph.Archive();
    processor.archive.copyTo(fork);
    var second = new ContractProcessor().create(m("count", 2));
    fork.add(second.state);
    assertFalse(processor.archive.versions.containsKey(second.identity()));
    fork.roots.remove(first.blueId());
    assertEquals(first.identity(), processor.archive.bindings().get(first.blueId()));
  }

  @Test
  void ambiguousRootsRequireTheSameExplicitSelectionInEveryBranch() {
    ContractProcessor processor = new ContractProcessor();
    var first = processor.create(m("count", 1));
    var second = first.state.copy();
    second.revision++;
    processor.archive.add(second);
    String root = first.blueId();
    assertFalse(processor.archive.bindings().containsKey(root));
    assertEquals(
        "AMBIGUOUS_PROCESS_VIEW",
        assertThrows(ContractModel.Error.class, () -> processor.archive.get(root)).code);
    ContractGraph.Archive fork = new ContractGraph.Archive();
    processor.archive.copyTo(fork);
    fork.choices.put(root, second.identity());
    assertEquals(second.identity(), fork.get(root).identity());
    assertFalse(processor.archive.bindings().containsKey(root));
    processor.archive.choices.put(root, first.identity());
    assertEquals(first.identity(), processor.archive.get(root).identity());
    assertEquals(second.identity(), fork.get(root).identity());
    assertEquals(
        "UNKNOWN_PROCESS_VIEW",
        assertThrows(ContractModel.Error.class, () -> fork.restrict(Map.of(root, "missing"))).code);
  }

  @Test
  void copyingIntoAnExistingArchiveKeepsItsUnrelatedEntriesAndIsolatesLaterWrites() {
    ContractProcessor source = new ContractProcessor(), target = new ContractProcessor();
    var first = source.create(m("count", 1));
    var second = target.create(m("count", 2));
    source.archive.copyTo(target.archive);
    assertEquals(first.identity(), target.archive.get(first.blueId()).identity());
    assertEquals(second.identity(), target.archive.get(second.blueId()).identity());
    assertNull(source.archive.get(second.blueId()));
    var later = source.create(m("count", 3));
    assertNull(target.archive.get(later.blueId()));
    target.archive.versions.clear();
    target.archive.roots.clear();
    assertEquals(first.identity(), source.archive.get(first.blueId()).identity());
    assertEquals(later.identity(), source.archive.get(later.blueId()).identity());
  }
}
