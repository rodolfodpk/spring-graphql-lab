package rdpk.pricing;

public final class PricingException extends RuntimeException {

    private final String code;

    public PricingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
