package rdpk.model;

public sealed interface CatalogItem permits Product, DigitalProduct {

    String id();

    String name();

    String description();

    CatalogCategory category();
}
