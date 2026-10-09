package org.example.pharmacy.otc.services.payloads.requests;

import java.math.BigDecimal;
import java.util.List;

public class OtcSaleCompleteRequest {
    public BigDecimal amountReceived;
    /** Optional discount subtracted from line total before payment check. */
    public BigDecimal discount;
    public String paymentForm;
    public String receivedBy;
    public String notes;
    /** Visit id when billing doctor prescriptions at the counter. */
    public Long visitId;
    /** Patient/client to tag the OTC sale / unpaid balance. */
    public Long patientId;
    public String patientName;
    /**
     * When false, stock is reduced and DSP is set but no visit payment is recorded
     * (dispensed, money not received). Null/true keeps the default paid flow.
     */
    public Boolean recordPayment;
    public List<OtcSaleLineRequest> lines;
}
