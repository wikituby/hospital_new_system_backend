package org.example.inventory.stock.services.payloads.responses.dtos;

import org.example.inventory.stock.domains.StockItemBarcode;

public class StockItemBarcodeDTO {
    public Long id;
    public String code;
    public Integer sortOrder;

    public StockItemBarcodeDTO() {
    }

    public StockItemBarcodeDTO(StockItemBarcode entity) {
        this.id = entity.id;
        this.code = entity.code;
        this.sortOrder = entity.sortOrder;
    }
}
