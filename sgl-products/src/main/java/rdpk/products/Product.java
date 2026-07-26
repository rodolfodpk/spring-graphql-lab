package rdpk.products;

public record Product(
        String id,
        String name,
        String description,
        CatalogCategory category,
        int weightGrams
) implements CatalogItem {
}
