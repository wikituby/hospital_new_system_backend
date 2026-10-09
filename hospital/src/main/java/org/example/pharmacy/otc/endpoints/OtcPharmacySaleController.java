package org.example.pharmacy.otc.endpoints;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.pharmacy.otc.services.OtcPharmacySaleService;
import org.example.pharmacy.otc.services.payloads.requests.OtcSaleCompleteRequest;
import org.example.pharmacy.otc.services.payloads.responses.OtcPharmacySaleDTO;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Path("pharmacy-otc")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Pharmacy OTC", description = "Over-the-counter sales without patient visits")
public class OtcPharmacySaleController {

    @Inject
    OtcPharmacySaleService otcPharmacySaleService;

    @POST
    @Path("complete-sale")
    @Transactional
    @Operation(summary = "Complete OTC sale", description = "Sell items, record payment, and reduce stock — no patient or visit.")
    @APIResponse(description = "Successful", responseCode = "200", content = @Content(schema = @Schema(implementation = OtcPharmacySaleDTO.class)))
    public Response completeSale(OtcSaleCompleteRequest request) {
        return otcPharmacySaleService.completeSale(request);
    }

    @GET
    @Path("sales")
    @Transactional
    @Operation(summary = "List pharmacy sold lines", description = "OTC counter and doctor prescription lines by visit/sale date range.")
    @APIResponse(description = "Successful", responseCode = "200")
    public Response listSales(
            @QueryParam("datefrom") String dateFrom,
            @QueryParam("dateto") String dateTo) {
        return otcPharmacySaleService.listSales(parseDate(dateFrom), parseDate(dateTo));
    }

    @GET
    @Path("doctor-prescriptions/pending")
    @Transactional
    @Operation(summary = "List pending doctor prescriptions", description = "Pending treatment lines for visits whose patients are currently queued to the pharmacy department.")
    @APIResponse(description = "Successful", responseCode = "200")
    public Response listPendingDoctorPrescriptions(@QueryParam("toModuleId") Long toModuleId) {
        return otcPharmacySaleService.listPendingDoctorPrescriptions(toModuleId);
    }

    @DELETE
    @Path("sold-line/{id}")
    @Transactional
    @Operation(summary = "Delete sold line (MD only)", description = "Remove one sold item line and restore deducted stock. Source must be OTC or RX.")
    @APIResponse(description = "Successful", responseCode = "200")
    public Response deleteSoldLine(
            @PathParam("id") Long lineId,
            @QueryParam("source") String source) {
        return otcPharmacySaleService.deleteSoldLine(lineId, source);
    }

    @POST
    @Path("sold-line/{id}/reverse")
    @Transactional
    @Operation(summary = "Reverse sold line", description = "Restore stock and mark the line as reversed (kept in history).")
    @APIResponse(description = "Successful", responseCode = "200")
    public Response reverseSoldLine(
            @PathParam("id") Long lineId,
            @QueryParam("source") String source) {
        return otcPharmacySaleService.reverseSoldLine(lineId, source);
    }

    @POST
    @Path("pay-client-debt/{patientId}")
    @Transactional
    @Operation(summary = "Pay pharmacy client debt", description = "Apply payment to unpaid OTC sales and reduce client account debt.")
    @APIResponse(description = "Successful", responseCode = "200")
    public Response payClientDebt(
            @PathParam("patientId") Long patientId,
            org.example.finance.payments.cash.services.payloads.requests.PaymentRequest request) {
        return otcPharmacySaleService.payClientDebt(patientId, request);
    }

    @GET
    @Path("stock-batch/{id}/defaults")
    @Transactional
    @Operation(summary = "OTC line defaults for a stock batch", description = "Returns dosage unit, route, and dose strength to prefill the OTC sale form when an item is selected.")
    @APIResponse(description = "Successful", responseCode = "200")
    public Response getStockBatchDefaults(@PathParam("id") Long stockBatchId) {
        return otcPharmacySaleService.getStockBatchDefaults(stockBatchId);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
