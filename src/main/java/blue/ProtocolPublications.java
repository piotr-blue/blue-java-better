package blue;

/** The one canonical package set, verified against the selected archive bytes. */
final class ProtocolPublications {
  private ProtocolPublications() {}

  public static BluePackage language() {
    return DeterministicPublications.publication("blue-language");
  }

  public static BluePackage bex() {
    return DeterministicPublications.publication("blue-bex");
  }

  public static BluePackage contracts() {
    return DeterministicPublications.publication("blue-contracts");
  }

  public static BluePackage bex(ContractsEdition edition) {
    return bex();
  }

  public static BluePackage contracts(ContractsEdition edition) {
    return contracts();
  }
}
