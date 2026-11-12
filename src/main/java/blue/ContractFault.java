package blue;

import java.util.Map;

/** A stable Contracts/Coordination failure category and detached diagnostic context. */
class ContractFault extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;
  private final String contextJson;

  public ContractFault(String code, Map<String, Object> context) {
    super(code);
    this.code = code;
    this.contextJson = ContractWire.write(context);
  }

  public String code() {
    return code;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> context() {
    return (Map<String, Object>) ContractWire.json(contextJson);
  }
}
