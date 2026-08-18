package rdpk.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Unlike the records around it, this type carries real behaviour: the stable extension code that
 * every inventory error contract depends on. The subgraph tests exercise it, but JaCoCo attributes
 * that to the subgraph rather than here, so it is worth covering where it lives.
 */
class InventoryExceptionTest {

    @Test
    void carriesAStableCodeAlongsideTheMessage() {
        InventoryException exception =
                new InventoryException("INVENTORY_UNAVAILABLE", "Warehouse request failed");

        assertEquals("INVENTORY_UNAVAILABLE", exception.code());
        assertEquals("Warehouse request failed", exception.getMessage());
    }

    @Test
    void retainsTheUpstreamCauseWhenOneIsSupplied() {
        Throwable cause = new IllegalStateException("connection reset");
        InventoryException exception =
                new InventoryException("INVENTORY_UNAVAILABLE", "Supplier request failed", cause);

        assertSame(cause, exception.getCause());
        assertEquals("INVENTORY_UNAVAILABLE", exception.code());
    }
}
