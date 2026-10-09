package org.example.procedure.itemUsedInProcedure.services.payloads.requests;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

public class ItemUsedRequest {
    @Schema(example = "1")
    public Long procedureId;

    @Schema(example = "1")
    public Long unitSellingModelId;

    @Schema(example = "1")
    public Long itemId;

    @Schema(example = "Labour")
    public String feeName;

    @Schema(example = "FEE")
    public String lineType;

    @Schema(example = "5")
    public int quantityUsed;

    @Schema(example = "1500")
    public BigDecimal unitCostPrice;

    @Schema(example = "false")
    public Boolean excluded;
}










