package blue;

/** Installed native rules, independent of a distribution's protocol wrapper identity. */
enum ContractsEdition {
  DETERMINISTIC("AYqefXDffJR23qq1exeZN33bNVxT6ZiraXtJg9bk4Vup", 10_000_000);
  private final String specification;
  private final long maximumGas;

  ContractsEdition(String specification, long maximumGas) {
    this.maximumGas = maximumGas;
    this.specification = specification;
  }

  public boolean finalGas() {
    return true;
  }

  public boolean deterministic() {
    return true;
  }

  public long maximumGas() {
    return maximumGas;
  }

  public String specificationBlueId() {
    return specification;
  }
}
