package rdpk.model;

/**
 * Carries a stable extension code, exactly like {@link PricingException}. Converting it into a
 * GraphQL error stays the controller's job, so this remains a plain Java type.
 */
public final class InventoryException extends RuntimeException {

    private final String code;

    public InventoryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public InventoryException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
