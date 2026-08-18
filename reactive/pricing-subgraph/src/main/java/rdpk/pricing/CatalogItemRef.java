package rdpk.pricing;

import rdpk.model.CatalogCategory;

import org.jspecify.annotations.Nullable;

public record CatalogItemRef(String id, @Nullable CatalogCategory category) {
}
