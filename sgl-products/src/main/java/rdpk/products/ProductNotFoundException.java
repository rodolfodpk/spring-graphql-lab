package rdpk.products;

public final class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String id) {
        super("Catalog item was not found: " + id);
    }
}
