package org.example.treatment.services.payloads.responses;
import jakarta.persistence.Column;
import org.example.treatment.domains.TreatmentRequested;

import java.math.BigDecimal;

public class TreatmentRequestedDTO {
    public Long id;
    public BigDecimal quantity;
    public BigDecimal unitSellingPrice;
    public BigDecimal totalAmount;
    public BigDecimal amountPaid;
    public BigDecimal unitCostPrice;
    public BigDecimal provisionalTotalAmount;
    public String itemName;
    public Integer shelfNumber;
    public BigDecimal provisionalQuantity;
    public BigDecimal totalUnits;

    public BigDecimal availableQuantity;

    public BigDecimal lastUpDateQuantity;

    public BigDecimal lastStockAtHand;

    public BigDecimal amountPerFrequencyValue;

    public String amountPerFrequencyUnit;

    public BigDecimal frequencyValue;

    public String frequencyUnit;

    public BigDecimal durationValue;

    public BigDecimal lastUnitValue;

    public String durationUnit;

    public Long itemId;

    public Long stockBatchId;

    public BigDecimal unitBuy;

    public String status;
    public Boolean prescribed;
    public Boolean paid;
    public Boolean dispensed;
    public Boolean administered;
    public Boolean given;
    public String instructions;
    public String route;
    public Long unitSellingModelId;
    public Long diagnosisId;
    public String diagnosisName;

    public TreatmentRequestedDTO(TreatmentRequested treatmentRequested) {
        this.id = treatmentRequested.id;
        this.itemId = treatmentRequested.itemId;
        this.stockBatchId = treatmentRequested.stockBatch != null
                ? treatmentRequested.stockBatch.id
                : null;

        this.lastStockAtHand = treatmentRequested.lastStockAtHand;
        this.amountPerFrequencyValue = treatmentRequested.amountPerFrequencyValue;
        this.amountPerFrequencyUnit = treatmentRequested.amountPerFrequencyUnit;

        this.frequencyValue = treatmentRequested.frequencyValue;
        this.durationValue = treatmentRequested.durationValue;
        this.frequencyUnit = treatmentRequested.frequencyUnit;

        this.lastUnitValue = treatmentRequested.lastUnitValue;
        this.totalUnits = treatmentRequested.totalUnits;


        this.durationUnit = treatmentRequested.durationUnit;


        this.lastUpDateQuantity = treatmentRequested.lastUpDateQuantity;
        this.quantity = treatmentRequested.quantity;
        this.shelfNumber = treatmentRequested.getShelfNumber(); // Use getter method
        this.availableQuantity = treatmentRequested.availableQuantity;
        this.unitBuy = treatmentRequested.unitBuy;

        this.provisionalQuantity = treatmentRequested.provisionalQuantity;
        this.unitSellingPrice = treatmentRequested.unitSellingPrice;
        this.provisionalTotalAmount = treatmentRequested.provisionalTotalAmount;
        this.totalAmount = treatmentRequested.totalAmount;
        this.amountPaid = treatmentRequested.amountPaid;
        this.unitCostPrice = treatmentRequested.unitBuy;
        this.itemName = treatmentRequested.itemName;
        this.status = treatmentRequested.status;
        this.prescribed = treatmentRequested.prescribed == null || Boolean.TRUE.equals(treatmentRequested.prescribed);
        this.paid = treatmentRequested.paid == null || Boolean.TRUE.equals(treatmentRequested.paid);
        this.dispensed = treatmentRequested.isDispensedOrGiven();
        this.administered = Boolean.TRUE.equals(treatmentRequested.administered);
        this.given = treatmentRequested.given == null || Boolean.TRUE.equals(treatmentRequested.given);
        this.instructions = treatmentRequested.instructions;
        this.route = treatmentRequested.route;
        this.unitSellingModelId = treatmentRequested.unitSellingModelId;
        if (treatmentRequested.diagnosis != null) {
            this.diagnosisId = treatmentRequested.diagnosis.id;
            this.diagnosisName = treatmentRequested.diagnosis.name;
        }
    }
}





