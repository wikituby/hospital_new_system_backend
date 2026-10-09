package org.example.treatment.services.payloads.requests;

/** Pharmacist / billing lifecycle: status plus PD / DSP / ADM flags. */
public class TreatmentStatusUpdateRequest {
    public String status;
    public Boolean prescribed;
    public Boolean paid;
    public Boolean dispensed;
    public Boolean administered;
    /** True = given, false = not given. Omitted leaves the current value. */
    public Boolean given;
}
