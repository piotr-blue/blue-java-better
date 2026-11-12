package blue;

/** The one canonical package set, verified against the selected archive bytes. */
final class ProtocolPublications {
  private ProtocolPublications() {}

  public static BluePackage bex() {
    return DeterministicPublications.publication("blue-bex");
  }

  public static BluePackage contracts() {
    return DeterministicPublications.publication("blue-contracts");
  }
}
