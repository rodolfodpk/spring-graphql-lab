package rdpk.pricing;

import org.jspecify.annotations.Nullable;

public record CatalogItemRef(String id, @Nullable CatalogCategory category) {
}
