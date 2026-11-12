package blue;

/** The single supported semantic accounting policy. Representation and host I/O are unmetered. */
public enum GasSchedule {
  /** Full BEX and Contracts execution, with additive accounting across document boundaries. */
  STANDARD("contracts-semantic-gas");

  private final String id;

  GasSchedule(String id) {
    this.id = id;
  }

  /**
   * Returns the exact accounting identity persisted with a processing history.
   *
   * @return the stable semantic gas policy identity
   */
  public String id() {
    return id;
  }
}
