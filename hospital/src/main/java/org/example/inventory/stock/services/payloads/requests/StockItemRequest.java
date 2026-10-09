package org.example.inventory.stock.services.payloads.requests;

import java.math.BigDecimal;
import java.util.List;

public class StockItemRequest {
    public String stockItemName;
    /** System barcode / SKU. Kept once set; generated when blank. */
    public String barcode;
    /** Extra barcodes that also identify this item. */
    public List<StockItemBarcodeRequest> otherBarcodes;
    public Long brandId;
    public Long formulationId;
    public Long lowestPackageId;
    public BigDecimal totalCompositionValue;
    public Long totalCompositionUnitId;
    public BigDecimal reconstitutionValue;
    public Long reconstitutionUnitId;
    public Long itemCategoryId;
    public Long routeOfAdminId;

    // Updated fields by the stock batch service
    public String lastUnitOfSellMeasure;
    public BigDecimal lastUnitOfSellMeasureStrength;
    public Long lastUnitOfSellMeasureStrengthUnit;

    // Prescribing units
    public Long lastUnitOfPrescribingMeasureStrengthUnit;
    public String prescribingPeriodBasis;
    public String prescribingDosageJson;
    public String prescribingFrequencyMode;
    public String prescribingFrequencyFixed;
    public String prescribingFrequencyCustomHours;

    // Others - medical information fields
    public String indication;
    /** Structured indication lines (preferred). When present, replaces legacy single indication TEXT. */
    public List<StockItemIndicationRequest> indications;
    public String image;
    public String contraIndication;
    public String drugIteractions;
    public String description;
    public String pharmacodynamics;
    public String pharmacokinetics;
    public String adverseEffects;
    public String dosage;
    public String notes;
    public String methodOfReconstitution;
    public String descriptionBeforeReconstitution;
    public String descriptionAfterReconstitution;
    public String storage;
    public String antiDote;

    /** Active-ingredient rows; element type is StockItemIngredientRequest. */
    public List<StockItemIngredientRequest> ingredients;
}
