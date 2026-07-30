package rdpk.products;

public record DigitalProduct(
        String id,
        String name,
        String description,
        CatalogCategory category,
        String downloadFormat
) implements CatalogItem {
}
