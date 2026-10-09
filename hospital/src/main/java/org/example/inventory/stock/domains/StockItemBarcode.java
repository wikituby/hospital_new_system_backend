package org.example.inventory.stock.domains;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

/**
 * An extra barcode that also identifies a stock/consumable item.
 * The item's own system barcode lives on {@link StockItem#barcode}.
 */
@Entity
public class StockItemBarcode extends PanacheEntity {

    @Column(nullable = false)
    public Long stockItemId;

    @Column(nullable = false, unique = true, length = 64)
    public String code;

    @Column
    public Integer sortOrder;
}
