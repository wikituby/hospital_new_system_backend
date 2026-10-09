package org.example.procedure.itemUsedInProcedure.services.payloads.responses;

import org.example.procedure.itemUsedInProcedure.domains.ItemUsed;

import java.math.BigDecimal;

public class ItemUsedDTO {
    public Long id;
    public Long procedureId;
    public Long unitSellingModelId;
    public Long itemId;
    public String lineType;
    public BigDecimal quantityUsed;
    public BigDecimal unitCostPrice;
    public String procedureName;
    public String itemName;
    public Boolean excluded;
    /** True when this line comes from the service's own items. */
    public Boolean fromServiceDefault;
    /** True when this model changed the service default for this item. */
    public Boolean overridden;

    public ItemUsedDTO(ItemUsed itemUsed) {
        if (itemUsed != null) {
            this.id = itemUsed.id;
            this.procedureId = itemUsed.procedureId;
            this.unitSellingModelId = itemUsed.unitSellingModelId;
            this.itemId = itemUsed.itemId;
            this.lineType = itemUsed.lineType == null ? "ITEM" : itemUsed.lineType;
            this.quantityUsed = itemUsed.quantityUsed;
            this.unitCostPrice = itemUsed.unitCostPrice;
            this.procedureName = itemUsed.procedureName;
            this.itemName = itemUsed.itemName;
            this.excluded = itemUsed.excluded;
            this.fromServiceDefault = false;
            this.overridden = false;
        }
    }
}










