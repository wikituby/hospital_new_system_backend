package org.example.pharmacy.otc.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.example.configuration.handler.ResponseMessage;
import org.example.configuration.handler.ActionMessages;
import org.example.configuration.security.AuthenticatedUserResolver;
import org.example.user.domains.User;
import org.example.finance.billing.services.PatientBillingService;
import org.example.finance.payments.cash.services.payloads.requests.PaymentRequest;
import org.example.inventory.item.domain.Item;
import org.example.inventory.item.domain.repositories.ItemRepository;
import org.example.inventory.item.services.ItemUnitSellingModelService;
import org.example.inventory.item.services.ShopItemService;
import org.example.inventory.stock.domains.StockBatch;
import org.example.inventory.stock.domains.StockItem;
import org.example.inventory.stock.services.StockTrackingService;
import org.example.inventory.stock.services.UnitSellingModelService;
import org.example.pharmacy.otc.domains.OtcPharmacySale;
import org.example.pharmacy.otc.domains.OtcPharmacySaleLine;
import org.example.pharmacy.otc.services.payloads.requests.OtcSaleCompleteRequest;
import org.example.pharmacy.otc.services.payloads.requests.OtcSaleLineRequest;
import org.example.pharmacy.otc.services.payloads.responses.OtcPharmacySaleCompleteResult;
import org.example.pharmacy.otc.services.payloads.responses.OtcPharmacySaleDTO;
import org.example.pharmacy.otc.services.payloads.responses.OtcPharmacySaleLineDTO;
import org.example.pharmacy.otc.services.payloads.responses.OtcStockBatchDefaultsDTO;
import org.example.pharmacy.otc.services.payloads.responses.PharmacyDoctorPrescriptionDTO;
import org.example.pharmacy.sundry.domains.VisitSundry;
import org.example.queue.domains.HospitalModule;
import org.example.queue.domains.PatientQueueEntry;
import org.example.queue.domains.repositories.PatientQueueEntryRepository;
import org.example.subscription.services.FacilityBrandingService;
import org.example.subscription.services.SpecialPrivilegeService;
import org.example.treatment.domains.TreatmentRequested;
import org.example.treatment.services.TreatmentRequestService;
import org.example.visit.domains.PatientVisit;
import org.example.visit.services.PatientVisitService;
import org.example.visit.services.paloads.requests.PatientVisitRequest;
import org.example.visit.services.paloads.responses.PatientVisitDTO;
import org.example.finance.invoice.services.InvoiceService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class OtcPharmacySaleService {

    @Inject
    StockTrackingService stockTrackingService;

    /** Kept for CDI compatibility with older Quarkus-generated beans. */
    @Inject
    PatientQueueEntryRepository patientQueueEntryRepository;

    @Inject
    PatientBillingService patientBillingService;

    @Inject
    AuthenticatedUserResolver authenticatedUserResolver;

    @Inject
    TreatmentRequestService treatmentRequestService;

    @Inject
    UnitSellingModelService unitSellingModelService;

    @Inject
    ItemUnitSellingModelService itemUnitSellingModelService;

    @Inject
    ShopItemService shopItemService;

    @Inject
    ItemRepository itemRepository;

    @Inject
    FacilityBrandingService facilityBrandingService;

    @Inject
    PatientVisitService patientVisitService;

    @Inject
    SpecialPrivilegeService specialPrivilegeService;

    @Inject
    InvoiceService invoiceService;

    @Transactional
    public Response completeSale(OtcSaleCompleteRequest request) {
        if (request == null || request.lines == null || request.lines.isEmpty()) {
            return badRequest("Add at least one item to complete the sale.");
        }
        boolean collectPayment = !Boolean.FALSE.equals(request.recordPayment);
        if (collectPayment && (request.paymentForm == null || request.paymentForm.isBlank())) {
            return badRequest("Payment method is required.");
        }
        if (!collectPayment && (request.paymentForm == null || request.paymentForm.isBlank())) {
            request.paymentForm = "Not paid";
        }

        Map<Long, OtcSaleLineRequest> lineByBatchId = new LinkedHashMap<>();
        Map<Long, OtcSaleLineRequest> lineByItemId = new LinkedHashMap<>();
        List<OtcSaleLineRequest> prescriptionLines = new ArrayList<>();
        List<OtcSaleLineRequest> sundryLines = new ArrayList<>();

        for (OtcSaleLineRequest lineReq : request.lines) {
            if (lineReq == null) {
                continue;
            }
            if (Boolean.FALSE.equals(lineReq.given)) {
                continue;
            }
            if (lineReq.visitSundryId != null) {
                sundryLines.add(lineReq);
                continue;
            }
            if (lineReq.treatmentRequestedId != null) {
                prescriptionLines.add(lineReq);
                continue;
            }
            BigDecimal qty = roundQty(lineReq.quantity);
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                return badRequest("Quantity must be greater than zero.");
            }
            Long batchId = positiveId(lineReq.stockBatchId);
            Long itemId = positiveId(lineReq.itemId);
            if (batchId != null) {
                OtcSaleLineRequest existing = lineByBatchId.get(batchId);
                if (existing == null) {
                    OtcSaleLineRequest merged = new OtcSaleLineRequest();
                    merged.stockBatchId = batchId;
                    merged.quantity = qty;
                    copyPrescribingFields(merged, lineReq);
                    lineByBatchId.put(batchId, merged);
                } else {
                    existing.quantity = nz(existing.quantity).add(qty);
                    copyPrescribingFields(existing, lineReq);
                }
                continue;
            }
            if (itemId != null) {
                OtcSaleLineRequest existing = lineByItemId.get(itemId);
                if (existing == null) {
                    OtcSaleLineRequest merged = new OtcSaleLineRequest();
                    merged.itemId = itemId;
                    merged.quantity = qty;
                    copyPrescribingFields(merged, lineReq);
                    lineByItemId.put(itemId, merged);
                } else {
                    existing.quantity = nz(existing.quantity).add(qty);
                    copyPrescribingFields(existing, lineReq);
                }
                continue;
            }
            return badRequest("Each OTC line must include a stock batch or item id.");
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<ResolvedLine> resolved = new ArrayList<>();

        for (OtcSaleLineRequest merged : lineByBatchId.values()) {
            ResolvedLine resolvedLine = resolveStockBatchLine(merged);
            if (resolvedLine.error != null) {
                return badRequest(resolvedLine.error);
            }
            totalAmount = totalAmount.add(resolvedLine.lineAmount);
            resolved.add(resolvedLine);
        }

        for (OtcSaleLineRequest merged : lineByItemId.values()) {
            ResolvedLine resolvedLine = resolveShopItemLine(merged);
            if (resolvedLine.error != null) {
                return badRequest(resolvedLine.error);
            }
            totalAmount = totalAmount.add(resolvedLine.lineAmount);
            resolved.add(resolvedLine);
        }

        for (OtcSaleLineRequest rxReq : prescriptionLines) {
            ResolvedLine resolvedLine = resolvePrescriptionLine(rxReq);
            if (resolvedLine.error != null) {
                return badRequest(resolvedLine.error);
            }
            totalAmount = totalAmount.add(resolvedLine.lineAmount);
            resolved.add(resolvedLine);
        }

        for (OtcSaleLineRequest sundryReq : sundryLines) {
            ResolvedLine resolvedLine = resolveVisitSundryLine(sundryReq);
            if (resolvedLine.error != null) {
                return badRequest(resolvedLine.error);
            }
            totalAmount = totalAmount.add(resolvedLine.lineAmount);
            resolved.add(resolvedLine);
        }

        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return badRequest("Sale total must be greater than zero.");
        }

        BigDecimal discount = nz(request.discount);
        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            return badRequest("Discount cannot be negative.");
        }
        if (discount.compareTo(totalAmount) > 0) {
            discount = totalAmount;
        }
        BigDecimal amountDue = totalAmount.subtract(discount);

        BigDecimal amountReceived;
        if (!collectPayment) {
            amountReceived = request.amountReceived != null ? request.amountReceived : BigDecimal.ZERO;
            if (amountReceived.compareTo(BigDecimal.ZERO) < 0) {
                amountReceived = BigDecimal.ZERO;
            }
        } else {
            amountReceived = request.amountReceived != null
                    ? request.amountReceived
                    : amountDue;
            if (amountReceived.compareTo(BigDecimal.ZERO) < 0) {
                return badRequest("Amount received cannot be negative.");
            }
        }
        Long patientId = positiveId(request.patientId);
        // Visit prescriptions are already tagged to a client — resolve if missing.
        if (patientId == null && request.visitId != null) {
            PatientVisit visitForPatient = PatientVisit.findById(request.visitId);
            if (visitForPatient != null && visitForPatient.patient != null) {
                patientId = visitForPatient.patient.id;
            }
        }
        // No-payment dispense: never require picking a client again.
        if (collectPayment && amountReceived.compareTo(amountDue) < 0 && patientId == null
                && request.visitId == null) {
            return badRequest("Select a client to tag unpaid balance, or pay the full amount.");
        }
        if (amountReceived.compareTo(amountDue) > 0) {
            // keep change; no-op
        }

        OtcPharmacySale sale = new OtcPharmacySale();
        sale.salePlainNo = nextSalePlainNo();
        sale.saleDate = LocalDate.now();
        sale.saleTime = LocalTime.now();
        sale.totalAmount = amountDue;
        sale.amountReceived = amountReceived;
        sale.changeAmount = amountReceived.compareTo(amountDue) > 0
                ? amountReceived.subtract(amountDue)
                : BigDecimal.ZERO;
        sale.paymentForm = request.paymentForm.trim();
        sale.receivedBy = request.receivedBy != null ? request.receivedBy.trim() : null;
        String notes = request.notes;
        if (!collectPayment) {
            String unpaidNote = "Dispensed — money not received";
            notes = (notes == null || notes.isBlank())
                    ? unpaidNote
                    : notes.trim() + " | " + unpaidNote;
        }
        if (discount.compareTo(BigDecimal.ZERO) > 0) {
            String discountNote = "Discount: " + discount;
            notes = (notes == null || notes.isBlank())
                    ? discountNote
                    : notes.trim() + " | " + discountNote;
        }
        // Visit invoice keeps the balance when money is not collected — do not
        // bump patient.totalAmountDue for no-payment dispenses.
        BigDecimal unpaid = collectPayment
                ? amountDue.subtract(amountReceived.min(amountDue))
                : BigDecimal.ZERO;
        if (unpaid.compareTo(BigDecimal.ZERO) < 0) {
            unpaid = BigDecimal.ZERO;
        }
        if (collectPayment && unpaid.compareTo(BigDecimal.ZERO) > 0) {
            String debtNote = "Unpaid on account: " + unpaid;
            notes = (notes == null || notes.isBlank())
                    ? debtNote
                    : notes.trim() + " | " + debtNote;
        }
        sale.notes = notes;

        org.example.client.domains.Patient taggedPatient = null;
        if (patientId != null) {
            taggedPatient = org.example.client.domains.Patient.findById(patientId);
            if (taggedPatient == null) {
                return badRequest("Client not found (id " + patientId + ").");
            }
            sale.patientId = patientId;
            String resolvedName = trimOrNull(request.patientName);
            if (resolvedName == null) {
                String first = taggedPatient.patientFirstName != null ? taggedPatient.patientFirstName : "";
                String second = taggedPatient.patientSecondName != null ? taggedPatient.patientSecondName : "";
                resolvedName = (first + " " + second).trim();
            }
            sale.patientName = resolvedName.isBlank() ? null : resolvedName;
        } else if (request.patientName != null && !request.patientName.isBlank()) {
            sale.patientName = request.patientName.trim();
        }

        // OTC debt without an existing visit → create an "Over the counter" visit and
        // record lines as treatmentrequested so debt appears on the client's visits.
        String priorDebtWarning = null;
        PatientVisit otcDebtVisit = null;
        if (collectPayment
                && unpaid.compareTo(BigDecimal.ZERO) > 0
                && taggedPatient != null
                && request.visitId == null) {
            Response debtGate = gateOtcDebtAgainstPriorVisits(taggedPatient);
            if (debtGate != null) {
                return debtGate;
            }
            BigDecimal priorVisitDebt = invoiceService.calculateTotalBalanceDueForAllVisits(taggedPatient.id);
            BigDecimal accountDue = taggedPatient.totalAmountDue != null
                    ? taggedPatient.totalAmountDue
                    : BigDecimal.ZERO;
            if (priorVisitDebt.compareTo(BigDecimal.ZERO) > 0 || accountDue.compareTo(BigDecimal.ZERO) > 0) {
                priorDebtWarning = "Client has a debt on one of the visits";
            }
            otcDebtVisit = createOtcCounterVisit(taggedPatient);
            if (otcDebtVisit == null || otcDebtVisit.id == null) {
                return badRequest("Could not create over-the-counter visit for this debt.");
            }
            request.visitId = otcDebtVisit.id;
            sale.visitId = otcDebtVisit.id;
        }

        if (request.visitId != null) {
            sale.visitId = request.visitId;
            if (sale.patientName == null) {
                sale.patientName = trimOrNull(request.patientName);
            }
            PatientVisit visit = PatientVisit.findById(request.visitId);
            if (visit != null && visit.visitNumber > 0) {
                sale.saleNo = "VISIT-" + visit.visitNumber;
            } else {
                sale.saleNo = "VISIT-" + request.visitId;
            }
        } else {
            sale.saleNo = "OTC-" + sale.salePlainNo;
        }
        sale.persist();

        for (ResolvedLine r : resolved) {
            if (r.visitSundry != null) {
                recordVisitSundrySaleLine(sale, r);
            } else if (r.shopItem != null) {
                deductShopItemAndRecord(sale, r);
            } else {
                deductBatchAndRecord(sale, r);
            }
            if (!collectPayment && r.treatment != null) {
                r.treatment.paid = false;
                r.treatment.persist();
            }
        }

        // Attach OTC debt sale lines as treatments on the new (or linked) visit invoice.
        if (otcDebtVisit != null) {
            for (ResolvedLine r : resolved) {
                if (r.visitSundry != null) {
                    continue;
                }
                createOtcDebtTreatment(otcDebtVisit, r);
            }
            invoiceService.syncInvoiceTotalsForVisit(otcDebtVisit.id);
        }

        // Prefer visit invoice balance over raw patient.totalAmountDue bump when we created an OTC visit.
        if (collectPayment && taggedPatient != null && unpaid.compareTo(BigDecimal.ZERO) > 0
                && otcDebtVisit == null && request.visitId == null) {
            BigDecimal current = taggedPatient.totalAmountDue != null
                    ? taggedPatient.totalAmountDue
                    : BigDecimal.ZERO;
            taggedPatient.totalAmountDue = current.add(unpaid);
            taggedPatient.persist();
        }

        OtcPharmacySaleCompleteResult result = new OtcPharmacySaleCompleteResult(new OtcPharmacySaleDTO(sale));
        String message = collectPayment
                ? "OTC sale completed — " + sale.saleNo
                : "Prescription dispensed (no payment) — " + sale.saleNo;
        if (priorDebtWarning != null) {
            message = priorDebtWarning + ". " + message;
        }
        if (collectPayment && unpaid.compareTo(BigDecimal.ZERO) > 0 && sale.patientName != null) {
            if (otcDebtVisit != null) {
                message += ". Balance Sh:" + unpaid + " recorded on Over the counter visit for "
                        + sale.patientName;
            } else {
                message += ". Balance Sh:" + unpaid + " tagged to " + sale.patientName;
            }
        }

        if (request.visitId != null && amountDue.compareTo(BigDecimal.ZERO) > 0) {
            List<Long> treatmentIds = prescriptionLines.stream()
                    .map(line -> line.treatmentRequestedId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (otcDebtVisit != null) {
                // OTC debt visit: treatments already marked given/paid; record only cash received.
                invoiceService.syncInvoiceTotalsForVisit(request.visitId);
                if (amountReceived.compareTo(BigDecimal.ZERO) > 0) {
                    PaymentRequest paymentTemplate = PatientBillingService.pharmacyPaymentTemplate(
                            request.paymentForm,
                            request.receivedBy,
                            request.notes != null ? request.notes : "OTC counter payment");
                    boolean paymentRecorded = patientBillingService.settlePharmacyDispense(
                            request.visitId,
                            treatmentIds,
                            amountReceived,
                            paymentTemplate);
                    result.visitPaymentRecorded = paymentRecorded;
                    if (paymentRecorded) {
                        message += ". Partial visit payment recorded.";
                    }
                } else {
                    result.visitPaymentRecorded = false;
                }
            } else if (collectPayment) {
                PaymentRequest paymentTemplate = PatientBillingService.pharmacyPaymentTemplate(
                        request.paymentForm,
                        request.receivedBy,
                        request.notes);
                boolean paymentRecorded = patientBillingService.settlePharmacyDispense(
                        request.visitId,
                        treatmentIds,
                        amountDue,
                        paymentTemplate);
                result.visitPaymentRecorded = paymentRecorded;
                if (paymentRecorded) {
                    message += ". Visit payment recorded.";
                }
            } else {
                patientBillingService.syncInvoiceAfterPharmacyDispense(request.visitId);
                result.visitPaymentRecorded = false;
                message += ". No payment recorded — balance remains on visit invoice.";
            }
            PatientVisit visit = PatientVisit.findById(request.visitId);
            if (visit != null) {
                result.amountPaid = visit.amountPaid;
                result.balanceDue = visit.balanceDue;
                result.totalAmount = visit.totalAmount;
                result.visitId = visit.id;
            }
        }

        return Response.ok(new ResponseMessage(message, result)).build();
    }

    @Transactional
    public Response listSales(LocalDate dateFrom, LocalDate dateTo) {
        LocalDate from = dateFrom != null ? dateFrom : LocalDate.now().minusDays(7);
        LocalDate to = dateTo != null ? dateTo : LocalDate.now();

        List<OtcPharmacySaleLineDTO> rows = new ArrayList<>();

        List<OtcPharmacySaleLine> lines = OtcPharmacySaleLine.find(
                "sale.saleDate >= ?1 and sale.saleDate <= ?2 order by sale.saleDate desc, sale.saleTime desc, id desc",
                from,
                to
        ).list();
        for (OtcPharmacySaleLine line : lines) {
            rows.add(new OtcPharmacySaleLineDTO(line));
        }

        Set<Long> visitIdsWithPharmacySale = new HashSet<>();
        @SuppressWarnings("unchecked")
        List<OtcPharmacySale> pharmacySales = OtcPharmacySale.find(
                "saleDate >= ?1 and saleDate <= ?2 and visitId is not null",
                from,
                to
        ).list();
        for (OtcPharmacySale sale : pharmacySales) {
            if (sale.visitId != null) {
                visitIdsWithPharmacySale.add(sale.visitId);
            }
        }

        @SuppressWarnings("unchecked")
        List<TreatmentRequested> treatments = TreatmentRequested.find(
                "visit.visitDate >= ?1 and visit.visitDate <= ?2 order by visit.visitDate desc, id desc",
                from,
                to
        ).list();
        for (TreatmentRequested treatment : treatments) {
            if (!isDispensedStatus(treatment.status)) {
                continue;
            }
            if (treatment.visit != null && visitIdsWithPharmacySale.contains(treatment.visit.id)) {
                continue;
            }
            rows.add(new OtcPharmacySaleLineDTO(treatment));
        }

        rows.sort(Comparator
                .comparing((OtcPharmacySaleLineDTO r) -> r.saleDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing((OtcPharmacySaleLineDTO r) -> r.saleTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing((OtcPharmacySaleLineDTO r) -> r.saleId, Comparator.nullsLast(Comparator.reverseOrder())));

        return Response.ok(new ResponseMessage(ActionMessages.FETCHED.label, rows)).build();
    }

    @Transactional
    public Response listPendingDoctorPrescriptions(Long toModuleId) {
        // Only visits on the pharmacy queue board (not cancelled/discharged).
        Set<Long> queuedVisitIds = pharmacyQueuedVisitIds(toModuleId);
        if (queuedVisitIds.isEmpty()) {
            return Response.ok(new ResponseMessage(ActionMessages.FETCHED.label, List.of())).build();
        }

        @SuppressWarnings("unchecked")
        List<TreatmentRequested> treatments = TreatmentRequested.find(
                "lower(status) = 'pending' and visit is not null and visit.visitStatus <> 'closed' "
                        + "and (dispensed is null or dispensed = false) "
                        + "and visit.id in ?1 "
                        + "order by visit.visitDate desc, visit.visitTime desc, id desc",
                queuedVisitIds
        ).list();
        List<PharmacyDoctorPrescriptionDTO> rows = new ArrayList<>(treatments.stream()
                .map(PharmacyDoctorPrescriptionDTO::new)
                .toList());

        // Undispensed visit sundries — same queue / dispense flow as drugs (stock already deducted).
        @SuppressWarnings("unchecked")
        List<VisitSundry> sundries = VisitSundry.find(
                "dispensed is null or dispensed = false order by id desc"
        ).list();
        for (VisitSundry sundry : sundries) {
            if (sundry == null || sundry.patientVisitId == null) {
                continue;
            }
            if (!queuedVisitIds.contains(sundry.patientVisitId)) {
                continue;
            }
            PatientVisit visit = PatientVisit.findById(sundry.patientVisitId);
            if (visit == null) {
                continue;
            }
            String status = visit.visitStatus != null ? visit.visitStatus.trim() : "";
            if ("closed".equalsIgnoreCase(status)) {
                continue;
            }
            rows.add(new PharmacyDoctorPrescriptionDTO(sundry, visit));
        }
        return Response.ok(new ResponseMessage(ActionMessages.FETCHED.label, rows)).build();
    }

    /**
     * Visit ids currently on the pharmacy queue board (not cancelled/discharged).
     * Uses {@code toModuleId} when provided; otherwise resolves the PHARMACY module.
     */
    private Set<Long> pharmacyQueuedVisitIds(Long toModuleId) {
        Long moduleId = toModuleId != null && toModuleId > 0 ? toModuleId : null;
        if (moduleId == null) {
            HospitalModule pharmacy = HospitalModule.find("code", "PHARMACY").firstResult();
            if (pharmacy == null) {
                pharmacy = HospitalModule.find("lower(routeKey) in ('pharmacy', 'otc-dispensary')").firstResult();
            }
            if (pharmacy != null) {
                moduleId = pharmacy.id;
            }
        }
        if (moduleId == null) {
            return Set.of();
        }

        // Match pharmacy queue board: keep COMPLETED until discharged/cancelled.
        @SuppressWarnings("unchecked")
        List<PatientQueueEntry> entries = patientQueueEntryRepository.list(
                "toModule.id = ?1 and upper(status) not in ('CANCELLED', 'DISCHARGED')",
                moduleId);
        Set<Long> visitIds = new HashSet<>();
        for (PatientQueueEntry entry : entries) {
            if (entry == null || entry.patientVisit == null || entry.patientVisit.id == null) {
                continue;
            }
            visitIds.add(entry.patientVisit.id);
        }
        return visitIds;
    }

    @Transactional
    public Response reverseSoldLine(Long lineId, String source) {
        if (lineId == null || lineId <= 0) {
            return badRequest("Line id is required.");
        }
        // OTC and RX prescription sale lines reverse the same way; DSP is cleared
        // when no active (non-reversed) sale lines remain for the treatment.
        return reverseOtcSoldLine(lineId);
    }

    /**
     * Apply a lump-sum payment to a pharmacy client's OTC unpaid sales (oldest first),
     * then reduce {@code patient.totalAmountDue}.
     */
    @Transactional
    public Response payClientDebt(Long patientId, PaymentRequest request) {
        if (patientId == null || patientId <= 0) {
            return badRequest("A valid client id is required.");
        }
        if (request == null || request.amountToPay == null
                || request.amountToPay.compareTo(BigDecimal.ZERO) <= 0) {
            return badRequest("Amount to pay must be greater than zero.");
        }

        org.example.client.domains.Patient patient =
                org.example.client.domains.Patient.findById(patientId);
        if (patient == null) {
            return badRequest("Client not found (id " + patientId + ").");
        }

        BigDecimal remaining = request.amountToPay;
        BigDecimal applied = BigDecimal.ZERO;

        @SuppressWarnings("unchecked")
        List<OtcPharmacySale> sales = OtcPharmacySale.find(
                "patientId = ?1 order by saleDate asc, saleTime asc, id asc",
                patientId
        ).list();

        for (OtcPharmacySale sale : sales) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal due = nz(sale.totalAmount);
            BigDecimal paid = nz(sale.amountReceived).min(due);
            BigDecimal unpaid = due.subtract(paid);
            if (unpaid.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal slice = remaining.min(unpaid);
            sale.amountReceived = nz(sale.amountReceived).add(slice);
            sale.changeAmount = nz(sale.amountReceived).compareTo(due) > 0
                    ? nz(sale.amountReceived).subtract(due)
                    : BigDecimal.ZERO;
            String note = request.notes != null && !request.notes.isBlank()
                    ? request.notes.trim()
                    : "Debt payment";
            String form = request.paymentForm != null && !request.paymentForm.isBlank()
                    ? request.paymentForm.trim()
                    : "Cash";
            String extra = " | Debt pay " + slice + " via " + form + " (" + note + ")";
            sale.notes = sale.notes == null || sale.notes.isBlank()
                    ? extra.trim().replaceFirst("^\\|\\s*", "")
                    : sale.notes + extra;
            sale.persist();
            applied = applied.add(slice);
            remaining = remaining.subtract(slice);
        }

        BigDecimal patientDue = nz(patient.totalAmountDue);
        if (applied.compareTo(BigDecimal.ZERO) <= 0 && patientDue.compareTo(BigDecimal.ZERO) <= 0) {
            return badRequest("This client has no outstanding pharmacy debt.");
        }

        // Always reduce patient account debt by the amount applied to sales,
        // or by the requested amount when debt exists only on the patient record.
        BigDecimal reduceBy = applied.compareTo(BigDecimal.ZERO) > 0
                ? applied
                : remaining.min(patientDue);
        if (applied.compareTo(BigDecimal.ZERO) <= 0 && reduceBy.compareTo(BigDecimal.ZERO) > 0) {
            applied = reduceBy;
            remaining = remaining.subtract(reduceBy);
        }
        if (reduceBy.compareTo(BigDecimal.ZERO) > 0 && patientDue.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal next = patientDue.subtract(reduceBy.min(patientDue));
            if (next.compareTo(BigDecimal.ZERO) < 0) {
                next = BigDecimal.ZERO;
            }
            patient.totalAmountDue = next;
            patient.persist();
        }

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("patientId", patientId);
        result.put("amountApplied", applied);
        result.put("remainingUnapplied", remaining.max(BigDecimal.ZERO));
        result.put("balanceDue", nz(patient.totalAmountDue));
        return Response.ok(new ResponseMessage(
                "Debt payment of " + applied + " applied.",
                result)).build();
    }

    private Response reverseOtcSoldLine(Long lineId) {
        OtcPharmacySaleLine line = OtcPharmacySaleLine.findById(lineId);
        if (line == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ResponseMessage("Sold line not found.", null))
                    .build();
        }
        if (line.reversed) {
            return badRequest("This line is already reversed.");
        }

        BigDecimal qty = nz(line.quantity);
        if (line.stockBatch != null) {
            StockBatch batch = line.stockBatch;
            BigDecimal stockBefore = nz(batch.stockAtHand);
            batch.stockAtHand = stockBefore.add(qty);
            batch.persist();
            line.stockAtHandAfter = batch.stockAtHand;

            OtcPharmacySale sale = line.sale;
            Long saleRefId = sale != null ? sale.id : null;
            stockTrackingService.recordBatchMovement(
                    batch,
                    stockBefore,
                    batch.stockAtHand,
                    StockTrackingService.TX_IN,
                    qty,
                    StockTrackingService.SRC_OTC_PHARMACY_SALE_VOID,
                    saleRefId,
                    StockTrackingService.REF_OTC_PHARMACY_SALE
            );
        } else if (line.itemId != null) {
            Item item = itemRepository.findById(line.itemId);
            if (item == null) {
                return badRequest("Item not found for this sold line.");
            }
            shopItemService.updateItemStockAtHandBeforeUpdating(qty, item);
            line.stockAtHandAfter = nz(item.stockAtHand);
        } else {
            return badRequest("Sold line has no stock batch or item.");
        }

        OtcPharmacySale sale = line.sale;
        if (sale != null) {
            reverseSalePaymentForLine(sale, line);
        }

        line.reversed = true;
        line.persist();
        clearTreatmentDispenseIfNoActiveSaleLines(line.treatmentRequestedId, sale != null ? sale.visitId : null);
        return Response.ok(new ResponseMessage(
                "Sale line reversed, payment adjusted, and stock restored.",
                new OtcPharmacySaleLineDTO(line))).build();
    }

    /**
     * Reduce sale totals / amount received for a reversed line, and clear the
     * matching unpaid share from the tagged client's account.
     */
    private void reverseSalePaymentForLine(OtcPharmacySale sale, OtcPharmacySaleLine line) {
        BigDecimal saleDue = nz(sale.totalAmount);
        BigDecimal amountReceived = nz(sale.amountReceived);
        BigDecimal lineAmt = nz(line.totalAmount);

        BigDecimal activeLinesSum = BigDecimal.ZERO;
        if (sale.lines != null) {
            for (OtcPharmacySaleLine other : sale.lines) {
                if (other == null || other.reversed) {
                    continue;
                }
                activeLinesSum = activeLinesSum.add(nz(other.totalAmount));
            }
        }
        if (activeLinesSum.compareTo(BigDecimal.ZERO) <= 0) {
            activeLinesSum = lineAmt;
        }

        BigDecimal lineSaleShare = lineAmt;
        if (saleDue.compareTo(BigDecimal.ZERO) > 0 && activeLinesSum.compareTo(BigDecimal.ZERO) > 0) {
            lineSaleShare = saleDue.multiply(lineAmt)
                    .divide(activeLinesSum, 2, RoundingMode.HALF_UP);
            if (lineSaleShare.compareTo(saleDue) > 0) {
                lineSaleShare = saleDue;
            }
        } else if (saleDue.compareTo(BigDecimal.ZERO) <= 0) {
            lineSaleShare = BigDecimal.ZERO;
        }

        BigDecimal paidApplied = amountReceived.min(saleDue);
        BigDecimal paidShare = BigDecimal.ZERO;
        BigDecimal unpaidShare = BigDecimal.ZERO;
        if (saleDue.compareTo(BigDecimal.ZERO) > 0 && lineSaleShare.compareTo(BigDecimal.ZERO) > 0) {
            paidShare = lineSaleShare.multiply(paidApplied)
                    .divide(saleDue, 2, RoundingMode.HALF_UP);
            unpaidShare = lineSaleShare.subtract(paidShare);
            if (unpaidShare.compareTo(BigDecimal.ZERO) < 0) {
                unpaidShare = BigDecimal.ZERO;
            }
        }

        BigDecimal newTotal = saleDue.subtract(lineSaleShare);
        if (newTotal.compareTo(BigDecimal.ZERO) < 0) {
            newTotal = BigDecimal.ZERO;
        }
        BigDecimal newPaid = paidApplied.subtract(paidShare);
        if (newPaid.compareTo(BigDecimal.ZERO) < 0) {
            newPaid = BigDecimal.ZERO;
        }
        if (newTotal.compareTo(BigDecimal.ZERO) <= 0) {
            newTotal = BigDecimal.ZERO;
            newPaid = BigDecimal.ZERO;
        }

        sale.totalAmount = newTotal;
        sale.amountReceived = newPaid;
        sale.changeAmount = newPaid.compareTo(newTotal) > 0
                ? newPaid.subtract(newTotal)
                : BigDecimal.ZERO;
        sale.persist();

        if (sale.patientId != null && unpaidShare.compareTo(BigDecimal.ZERO) > 0) {
            org.example.client.domains.Patient patient =
                    org.example.client.domains.Patient.findById(sale.patientId);
            if (patient != null) {
                BigDecimal current = nz(patient.totalAmountDue);
                BigDecimal next = current.subtract(unpaidShare);
                if (next.compareTo(BigDecimal.ZERO) < 0) {
                    next = BigDecimal.ZERO;
                }
                patient.totalAmountDue = next;
                patient.persist();
            }
        }
    }

    @Transactional
    public Response deleteSoldLine(Long lineId, String source) {
        if (!isMdUser()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ResponseMessage("Only MD can delete sold item lines.", null))
                    .build();
        }
        if (lineId == null || lineId <= 0) {
            return badRequest("Line id is required.");
        }
        String normalizedSource = source != null ? source.trim().toUpperCase() : "";
        if ("RX".equals(normalizedSource)) {
            return deleteRxSoldLine(lineId);
        }
        if ("OTC".equals(normalizedSource)) {
            return deleteOtcSoldLine(lineId);
        }
        return badRequest("Source must be OTC or RX.");
    }

    @Transactional
    public Response getStockBatchDefaults(Long stockBatchId) {
        if (stockBatchId == null) {
            return badRequest("Stock batch id is required.");
        }
        StockBatch batch = StockBatch.findById(stockBatchId);
        if (batch == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ResponseMessage("Stock batch not found for ID: " + stockBatchId, null))
                    .build();
        }
        return Response.ok(new ResponseMessage(
                ActionMessages.FETCHED.label,
                new OtcStockBatchDefaultsDTO(batch))).build();
    }

    private ResolvedLine resolveStockBatchLine(OtcSaleLineRequest lineReq) {
        ResolvedLine result = new ResolvedLine();

        StockBatch batch = StockBatch.findById(lineReq.stockBatchId);
        if (batch == null) {
            result.error = "Stock batch not found (id " + lineReq.stockBatchId + ").";
            return result;
        }

        applyPrescribingDefaultsFromStockItem(lineReq, batch);

        BigDecimal qty = roundQty(lineReq.quantity);
        result.unitBuy = nz(batch.unitCostPrice);
        Long modelId = lineReq.unitSellingModelId != null ? lineReq.unitSellingModelId : batch.unitSellingModelId;
        result.unitSellingPrice = unitSellingModelService.resolveUnitSellingPrice(
                batch.stockItemId,
                modelId,
                batch.unitSellingPrice
        );
        result.lineRequest = lineReq;

        List<StockBatch> pool = findDeductionPool(batch);
        BigDecimal totalAvailable = totalPoolStock(pool);
        if (qty.compareTo(totalAvailable) > 0) {
            result.error = "Insufficient stock for "
                    + nzStr(batch.stockItemName)
                    + ". Available: " + totalAvailable;
            return result;
        }

        result.allocations = allocateAcrossStores(
                orderDeductionPool(batch, pool),
                qty,
                result.unitSellingPrice,
                result.unitBuy);
        result.quantity = qty;
        result.lineAmount = result.allocations.stream()
                .map(a -> a.lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return result;
    }

    private ResolvedLine resolveShopItemLine(OtcSaleLineRequest lineReq) {
        ResolvedLine result = new ResolvedLine();
        if (lineReq.itemId == null) {
            result.error = "Item id is required.";
            return result;
        }
        Item item = itemRepository.findById(lineReq.itemId);
        if (item == null) {
            result.error = "Item not found (id " + lineReq.itemId + ").";
            return result;
        }

        BigDecimal qty = roundQty(lineReq.quantity);
        BigDecimal available = nz(item.stockAtHand);
        if (qty.compareTo(available) > 0) {
            result.error = "Insufficient stock for " + nzStr(item.title)
                    + ". Available: " + available;
            return result;
        }

        applyPrescribingDefaultsFromShopItem(lineReq, item);

        result.shopItem = item;
        result.lineRequest = lineReq;
        result.quantity = qty;
        result.unitBuy = nz(item.costPrice);
        Long modelId = lineReq.unitSellingModelId != null ? lineReq.unitSellingModelId : item.unitSellingModelId;
        result.unitSellingPrice = itemUnitSellingModelService.resolveUnitSellingPrice(
                item.id,
                modelId,
                item.sellingPrice
        );
        if (result.unitSellingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            result.error = "Item has no selling price: " + nzStr(item.title);
            return result;
        }
        result.lineAmount = qty.multiply(result.unitSellingPrice);
        return result;
    }

    private ResolvedLine resolveVisitSundryLine(OtcSaleLineRequest lineReq) {
        ResolvedLine result = new ResolvedLine();
        if (lineReq.visitSundryId == null) {
            result.error = "Sundry line is missing visit sundry id.";
            return result;
        }
        VisitSundry sundry = VisitSundry.findById(lineReq.visitSundryId);
        if (sundry == null) {
            result.error = "Visit sundry not found (id " + lineReq.visitSundryId + ").";
            return result;
        }
        if (Boolean.TRUE.equals(sundry.dispensed)) {
            result.error = "Sundry already dispensed: "
                    + (sundry.itemName != null ? sundry.itemName : sundry.id);
            return result;
        }
        BigDecimal qty = lineReq.quantity != null ? roundQty(lineReq.quantity) : nz(sundry.quantityUsed);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            result.error = "Invalid quantity on sundry: "
                    + (sundry.itemName != null ? sundry.itemName : sundry.id);
            return result;
        }
        BigDecimal unitSell = nz(sundry.unitSellingPrice);
        BigDecimal unitBuy = nz(sundry.unitCostPrice);
        result.visitSundry = sundry;
        result.lineRequest = lineReq;
        result.quantity = qty;
        result.unitBuy = unitBuy;
        result.unitSellingPrice = unitSell;
        result.lineAmount = qty.multiply(unitSell).setScale(2, RoundingMode.HALF_UP);
        return result;
    }

    /** Record sundry on the sale without deducting stock (already deducted at recording). */
    private void recordVisitSundrySaleLine(OtcPharmacySale sale, ResolvedLine r) {
        VisitSundry sundry = r.visitSundry;
        if (sundry == null) {
            return;
        }
        OtcPharmacySaleLine line = new OtcPharmacySaleLine();
        line.sale = sale;
        line.stockBatch = null;
        line.itemId = sundry.itemId;
        line.itemName = nzStr(sundry.itemName);
        line.quantity = r.quantity;
        line.unitBuy = r.unitBuy;
        line.unitSellingPrice = r.unitSellingPrice;
        line.totalAmount = r.lineAmount;
        line.stockAtHandAfter = null;
        line.visitSundryId = sundry.id;
        line.persist();
        sale.lines.add(line);

        sundry.dispensed = true;
        sundry.persist();
    }

    private ResolvedLine resolvePrescriptionLine(OtcSaleLineRequest lineReq) {
        ResolvedLine result = new ResolvedLine();
        if (lineReq.treatmentRequestedId == null) {
            result.error = "Prescription line is missing treatment id.";
            return result;
        }
        TreatmentRequested treatment = TreatmentRequested.findById(lineReq.treatmentRequestedId);
        if (treatment == null) {
            result.error = "Prescription not found (id " + lineReq.treatmentRequestedId + ").";
            return result;
        }

        BigDecimal qty = resolveDispenseQuantity(lineReq, treatment);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            result.error = "Invalid quantity on prescription: " + nzStr(treatment.itemName);
            return result;
        }

        result.unitBuy = nz(treatment.unitBuy);
        result.unitSellingPrice = nz(treatment.unitSellingPrice);
        result.lineRequest = lineReq;
        result.treatment = treatment;
        result.quantity = qty;

        if (facilityBrandingService.pharmacyUseStockBatchForDefaultFacility()) {
            return resolvePrescriptionStockBatchLine(result, lineReq, treatment, qty);
        }
        return resolvePrescriptionShopItemLine(result, lineReq, treatment, qty);
    }

    private ResolvedLine resolvePrescriptionStockBatchLine(
            ResolvedLine result,
            OtcSaleLineRequest lineReq,
            TreatmentRequested treatment,
            BigDecimal qty) {
        StockBatch batch = treatment.stockBatch;
        Long requestedBatchId = positiveId(lineReq.stockBatchId);
        if (batch == null && requestedBatchId != null) {
            batch = StockBatch.findById(requestedBatchId);
        }
        if (batch == null) {
            result.error = "Prescription has no stock batch: " + nzStr(treatment.itemName);
            return result;
        }

        List<StockBatch> pool = findItemDeductionPool(batch);
        BigDecimal totalAvailable = totalPoolStock(pool);
        if (qty.compareTo(totalAvailable) > 0) {
            result.error = "Insufficient stock for " + nzStr(treatment.itemName)
                    + ". Available: " + totalAvailable + ", Required: " + qty;
            return result;
        }

        result.allocations = allocateAcrossStores(
                orderPrescriptionDeductionPool(batch, pool),
                qty,
                result.unitSellingPrice,
                result.unitBuy);
        result.lineAmount = qty.multiply(result.unitSellingPrice);
        return result;
    }

    private ResolvedLine resolvePrescriptionShopItemLine(
            ResolvedLine result,
            OtcSaleLineRequest lineReq,
            TreatmentRequested treatment,
            BigDecimal qty) {
        Long itemId = positiveId(lineReq.itemId);
        if (itemId == null) {
            itemId = positiveId(treatment.itemId);
        }
        if (itemId == null) {
            result.error = "Prescription has no item: " + nzStr(treatment.itemName);
            return result;
        }
        Item item = itemRepository.findById(itemId);
        if (item == null) {
            result.error = "Item not found (id " + itemId + ").";
            return result;
        }

        BigDecimal available = nz(item.stockAtHand);
        if (qty.compareTo(available) > 0) {
            result.error = "Insufficient stock for " + nzStr(treatment.itemName)
                    + ". Available: " + available + ", Required: " + qty;
            return result;
        }

        if (result.unitSellingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            Long modelId = lineReq.unitSellingModelId != null
                    ? lineReq.unitSellingModelId
                    : treatment.unitSellingModelId != null
                            ? treatment.unitSellingModelId
                            : item.unitSellingModelId;
            result.unitSellingPrice = itemUnitSellingModelService.resolveUnitSellingPrice(
                    item.id,
                    modelId,
                    item.sellingPrice);
        }
        if (result.unitBuy.compareTo(BigDecimal.ZERO) <= 0) {
            result.unitBuy = nz(item.costPrice);
        }
        if (result.unitSellingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            result.error = "Item has no selling price: " + nzStr(treatment.itemName);
            return result;
        }

        result.shopItem = item;
        result.lineAmount = qty.multiply(result.unitSellingPrice);
        return result;
    }

    private Response deleteOtcSoldLine(Long lineId) {
        OtcPharmacySaleLine line = OtcPharmacySaleLine.findById(lineId);
        if (line == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ResponseMessage("Sold line not found.", null))
                    .build();
        }

        BigDecimal qty = nz(line.quantity);
        if (line.stockBatch != null) {
            StockBatch batch = line.stockBatch;
            BigDecimal stockBefore = nz(batch.stockAtHand);
            batch.stockAtHand = stockBefore.add(qty);
            batch.persist();

            OtcPharmacySale sale = line.sale;
            Long saleRefId = sale != null ? sale.id : null;
            stockTrackingService.recordBatchMovement(
                    batch,
                    stockBefore,
                    batch.stockAtHand,
                    StockTrackingService.TX_IN,
                    qty,
                    StockTrackingService.SRC_OTC_PHARMACY_SALE_VOID,
                    saleRefId,
                    StockTrackingService.REF_OTC_PHARMACY_SALE
            );
        } else if (line.itemId != null) {
            Item item = itemRepository.findById(line.itemId);
            if (item != null) {
                shopItemService.updateItemStockAtHandBeforeUpdating(qty, item);
            }
        } else {
            return badRequest("Sold line has no stock batch or item.");
        }

        OtcPharmacySale sale = line.sale;
        Long treatmentId = line.treatmentRequestedId;
        Long visitId = sale != null ? sale.visitId : null;
        if (sale != null) {
            sale.totalAmount = nz(sale.totalAmount).subtract(nz(line.totalAmount));
            if (nz(sale.amountReceived).compareTo(sale.totalAmount) > 0) {
                sale.changeAmount = sale.amountReceived.subtract(sale.totalAmount);
            } else {
                sale.changeAmount = BigDecimal.ZERO;
            }
            sale.lines.remove(line);
            if (sale.lines.isEmpty()) {
                sale.delete();
            } else {
                sale.persist();
            }
        }

        line.delete();
        clearTreatmentDispenseIfNoActiveSaleLines(treatmentId, visitId);
        return Response.ok(new ResponseMessage("Sold line removed and stock restored.", null)).build();
    }

    /**
     * When every pharmacy sale line for a prescription is reversed/removed, clear DSP
     * so the consultation PD/DSP/ADM column reflects that the drug is no longer dispensed.
     */
    private void clearTreatmentDispenseIfNoActiveSaleLines(Long treatmentRequestedId, Long visitId) {
        if (treatmentRequestedId == null || treatmentRequestedId <= 0) {
            return;
        }
        long active = OtcPharmacySaleLine.count(
                "treatmentRequestedId = ?1 and reversed = false",
                treatmentRequestedId);
        if (active > 0) {
            return;
        }
        TreatmentRequested treatment = TreatmentRequested.findById(treatmentRequestedId);
        if (treatment == null) {
            return;
        }
        treatment.dispensed = false;
        if (isDispensedStatus(treatment.status)) {
            treatment.status = "pending";
        }
        treatment.persist();
        if (visitId != null) {
            patientBillingService.syncInvoiceAfterPharmacyDispense(visitId);
        } else if (treatment.visit != null) {
            patientBillingService.syncInvoiceAfterPharmacyDispense(treatment.visit.id);
        }
    }

    private Response deleteRxSoldLine(Long treatmentId) {
        TreatmentRequested treatment = TreatmentRequested.findById(treatmentId);
        if (treatment == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ResponseMessage("Prescription line not found.", null))
                    .build();
        }
        if (!isDispensedStatus(treatment.status)) {
            return badRequest("Only dispensed prescription lines can be removed.");
        }
        return treatmentRequestService.deleteTreatmentRequestById(treatmentId);
    }

    private boolean isMdUser() {
        try {
            User user = authenticatedUserResolver.requireCurrentUser();
            return user.role != null && "md".equalsIgnoreCase(user.role.trim());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isDispensedStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim().toLowerCase();
        return "given".equals(normalized) || "dispensed".equals(normalized);
    }

    private void deductBatchAndRecord(OtcPharmacySale sale, ResolvedLine r) {
        if (r.shopItem != null) {
            deductShopItemAndRecord(sale, r);
            return;
        }
        if (r.allocations == null || r.allocations.isEmpty()) {
            return;
        }
        for (BatchAllocation allocation : r.allocations) {
            deductSingleAllocation(sale, r, allocation);
        }
        if (r.treatment != null) {
            r.treatment.quantity = r.quantity;
            r.treatment.totalAmount = r.lineAmount;
            r.treatment.status = "given";
            r.treatment.paid = true;
            r.treatment.dispensed = true;
            r.treatment.persist();
        }
    }

    private void deductShopItemAndRecord(OtcPharmacySale sale, ResolvedLine r) {
        Item item = r.shopItem;
        if (item == null) {
            return;
        }
        // Same pattern as treatment dispense for shop Item: stockAtHand -= qty only.
        shopItemService.updateItemStockAtHandAfterSelling(r.quantity, item);

        OtcPharmacySaleLine line = new OtcPharmacySaleLine();
        line.sale = sale;
        line.stockBatch = null;
        line.itemId = item.id;
        line.stockItemId = null;
        line.itemName = nzStr(item.title);
        line.quantity = r.quantity;
        line.unitBuy = r.unitBuy;
        line.unitSellingPrice = r.unitSellingPrice;
        line.totalAmount = r.lineAmount;
        line.stockAtHandAfter = nz(item.stockAtHand);
        if (r.treatment != null) {
            line.treatmentRequestedId = r.treatment.id;
        }
        // OTC counter sales do not store prescription dosage / duration fields.
        line.persist();
        sale.lines.add(line);
        if (r.treatment != null) {
            r.treatment.quantity = r.quantity;
            r.treatment.totalAmount = r.lineAmount;
            r.treatment.status = "given";
            r.treatment.paid = true;
            r.treatment.dispensed = true;
            r.treatment.persist();
        }
    }

    private void deductSingleAllocation(OtcPharmacySale sale, ResolvedLine r, BatchAllocation allocation) {
        StockBatch batch = allocation.batch;
        BigDecimal stockBefore = nz(batch.stockAtHand);
        batch.stockAtHand = stockBefore.subtract(allocation.quantity);
        batch.persist();

        OtcPharmacySaleLine line = new OtcPharmacySaleLine();
        line.sale = sale;
        line.stockBatch = batch;
        line.stockItemId = batch.stockItemId;
        line.itemName = nzStr(
                r.treatment != null ? r.treatment.itemName : batch.stockItemName);
        line.batchNumber = batch.batchNumber;
        line.storeId = batch.storeId;
        line.storeName = batch.storeName;
        line.quantity = allocation.quantity;
        line.unitBuy = allocation.unitBuy;
        line.unitSellingPrice = allocation.unitSellingPrice;
        line.totalAmount = allocation.lineAmount;
        line.stockAtHandAfter = batch.stockAtHand;
        if (r.treatment != null) {
            line.treatmentRequestedId = r.treatment.id;
        }
        if (r.lineRequest != null) {
            line.amountPerFrequencyValue = r.lineRequest.amountPerFrequencyValue;
            line.amountPerFrequencyUnit = trimOrNull(r.lineRequest.amountPerFrequencyUnit);
            line.frequencyValue = r.lineRequest.frequencyValue;
            line.frequencyUnit = r.lineRequest.frequencyUnit;
            line.durationValue = r.lineRequest.durationValue;
            line.durationUnit = r.lineRequest.durationUnit;
            line.totalUnits = r.lineRequest.totalUnits;
            line.instructions = trimOrNull(r.lineRequest.instructions);
            line.route = trimOrNull(r.lineRequest.route);
        } else if (r.treatment != null) {
            line.amountPerFrequencyValue = r.treatment.amountPerFrequencyValue;
            line.amountPerFrequencyUnit = trimOrNull(r.treatment.amountPerFrequencyUnit);
            line.frequencyValue = r.treatment.frequencyValue;
            line.frequencyUnit = parseFrequencyUnitInt(r.treatment.frequencyUnit);
            line.durationValue = r.treatment.durationValue;
            line.durationUnit = parseDurationUnitInt(r.treatment.durationUnit);
            line.totalUnits = r.treatment.totalUnits;
            line.instructions = trimOrNull(r.treatment.instructions);
            line.route = trimOrNull(r.treatment.route);
        }
        line.persist();
        sale.lines.add(line);

        stockTrackingService.recordBatchMovement(
                batch,
                stockBefore,
                batch.stockAtHand,
                StockTrackingService.TX_OUT,
                allocation.quantity,
                StockTrackingService.SRC_OTC_PHARMACY_SALE,
                sale.id,
                StockTrackingService.REF_OTC_PHARMACY_SALE
        );
    }

    /** Same item + batch number (+ expiry when present) across all stores. */
    @SuppressWarnings("unchecked")
    private List<StockBatch> findDeductionPool(StockBatch anchor) {
        if (anchor == null) {
            return List.of();
        }
        if (anchor.stockItemId == null) {
            return nz(anchor.stockAtHand).compareTo(BigDecimal.ZERO) > 0
                    ? List.of(anchor)
                    : List.of();
        }
        String batchNumber = trimOrNull(anchor.batchNumber);
        List<StockBatch> pool;
        if (batchNumber != null) {
            if (anchor.expiryDate != null) {
                pool = StockBatch.find(
                        "stockItemId = ?1 and batchNumber = ?2 and expiryDate = ?3",
                        anchor.stockItemId,
                        batchNumber,
                        anchor.expiryDate
                ).list();
            } else {
                pool = StockBatch.find(
                        "stockItemId = ?1 and batchNumber = ?2",
                        anchor.stockItemId,
                        batchNumber
                ).list();
            }
        } else {
            pool = List.of(anchor);
        }
        List<StockBatch> withStock = new ArrayList<>();
        for (StockBatch batch : pool) {
            if (batch != null && nz(batch.stockAtHand).compareTo(BigDecimal.ZERO) > 0) {
                withStock.add(batch);
            }
        }
        return withStock;
    }

    private BigDecimal totalPoolStock(List<StockBatch> pool) {
        BigDecimal total = BigDecimal.ZERO;
        for (StockBatch batch : pool) {
            total = total.add(nz(batch.stockAtHand));
        }
        return total;
    }

    private List<StockBatch> orderDeductionPool(StockBatch preferred, List<StockBatch> pool) {
        List<StockBatch> ordered = new ArrayList<>();
        if (preferred != null && nz(preferred.stockAtHand).compareTo(BigDecimal.ZERO) > 0) {
            ordered.add(preferred);
        }
        pool.stream()
                .filter(batch -> preferred == null || !preferred.id.equals(batch.id))
                .sorted(Comparator.comparing(
                        batch -> nzStr(batch.storeName),
                        String.CASE_INSENSITIVE_ORDER))
                .forEach(ordered::add);
        return ordered;
    }

    private BigDecimal resolveDispenseQuantity(OtcSaleLineRequest lineReq, TreatmentRequested treatment) {
        if (lineReq != null
                && lineReq.quantity != null
                && lineReq.quantity.compareTo(BigDecimal.ZERO) > 0) {
            return roundQty(lineReq.quantity);
        }
        return roundQty(treatment.quantity);
    }

    /** All in-stock batches for one item (any store / batch number), earliest expiry first. */
    @SuppressWarnings("unchecked")
    private List<StockBatch> findItemDeductionPool(StockBatch anchor) {
        if (anchor == null) {
            return List.of();
        }
        if (anchor.stockItemId == null) {
            return findDeductionPool(anchor);
        }
        List<StockBatch> pool = StockBatch.find(
                "stockItemId = ?1 order by expiryDate asc nulls last, id asc",
                anchor.stockItemId
        ).list();
        List<StockBatch> withStock = new ArrayList<>();
        for (StockBatch batch : pool) {
            if (batch != null && nz(batch.stockAtHand).compareTo(BigDecimal.ZERO) > 0) {
                withStock.add(batch);
            }
        }
        return withStock;
    }

    private List<StockBatch> orderPrescriptionDeductionPool(StockBatch preferred, List<StockBatch> pool) {
        List<StockBatch> ordered = new ArrayList<>();
        if (preferred != null && nz(preferred.stockAtHand).compareTo(BigDecimal.ZERO) > 0) {
            ordered.add(preferred);
        }
        pool.stream()
                .filter(batch -> preferred == null || !preferred.id.equals(batch.id))
                .forEach(ordered::add);
        return ordered;
    }

    private List<BatchAllocation> allocateAcrossStores(
            List<StockBatch> ordered,
            BigDecimal qty,
            BigDecimal unitSellingPrice,
            BigDecimal unitBuy) {
        List<BatchAllocation> allocations = new ArrayList<>();
        BigDecimal remaining = qty;
        for (StockBatch batch : ordered) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal available = nz(batch.stockAtHand);
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal take = remaining.min(available);
            BatchAllocation allocation = new BatchAllocation();
            allocation.batch = batch;
            allocation.quantity = take;
            allocation.unitSellingPrice = unitSellingPrice.compareTo(BigDecimal.ZERO) > 0
                    ? unitSellingPrice
                    : nz(batch.unitSellingPrice);
            allocation.unitBuy = unitBuy.compareTo(BigDecimal.ZERO) > 0
                    ? unitBuy
                    : nz(batch.unitCostPrice);
            allocation.lineAmount = take.multiply(allocation.unitSellingPrice);
            allocations.add(allocation);
            remaining = remaining.subtract(take);
        }
        return allocations;
    }

    private int nextSalePlainNo() {
        List<OtcPharmacySale> sales = OtcPharmacySale.find("order by salePlainNo desc").list();
        if (sales.isEmpty() || sales.get(0).salePlainNo == null) {
            return 1;
        }
        return sales.get(0).salePlainNo + 1;
    }

    private static BigDecimal roundQty(BigDecimal quantity) {
        if (quantity == null) {
            return BigDecimal.ZERO;
        }
        return quantity.setScale(0, RoundingMode.CEILING);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /** Treat missing or non-positive ids as absent (clients may send 0). */
    private static Long positiveId(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private static String nzStr(String value) {
        return value != null && !value.isBlank() ? value.trim() : "Item";
    }

    private static String trimOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void copyPrescribingFields(OtcSaleLineRequest target, OtcSaleLineRequest source) {
        if (target == null || source == null) {
            return;
        }
        target.amountPerFrequencyValue = source.amountPerFrequencyValue;
        target.amountPerFrequencyUnit = source.amountPerFrequencyUnit;
        target.frequencyValue = source.frequencyValue;
        target.frequencyUnit = source.frequencyUnit;
        target.durationValue = source.durationValue;
        target.durationUnit = source.durationUnit;
        target.totalUnits = source.totalUnits;
        target.instructions = source.instructions;
        target.route = source.route;
        target.unitSellingModelId = source.unitSellingModelId;
        target.given = source.given;
    }

    private static void applyPrescribingDefaultsFromShopItem(OtcSaleLineRequest lineReq, Item item) {
        if (lineReq == null || item == null) {
            return;
        }
        if (isBlank(lineReq.amountPerFrequencyUnit)) {
            lineReq.amountPerFrequencyUnit = item.dosageUnit != null ? item.dosageUnit : item.lastUnitOfMeasure;
        }
        if (lineReq.amountPerFrequencyValue == null && item.dosage != null) {
            lineReq.amountPerFrequencyValue = item.dosage;
        }
        if (isBlank(lineReq.route)) {
            lineReq.route = item.route;
        }
    }

    private static void applyPrescribingDefaultsFromStockItem(OtcSaleLineRequest lineReq, StockBatch batch) {
        if (lineReq == null || batch == null || batch.stockItemId == null) {
            return;
        }
        StockItem item = StockItem.findById(batch.stockItemId);
        if (item == null) {
            return;
        }
        if (isBlank(lineReq.amountPerFrequencyUnit)) {
            lineReq.amountPerFrequencyUnit = item.lastUnitOfPrescribingMeasureStrengthUnitTitle;
        }
        if (lineReq.amountPerFrequencyValue == null && item.lastUnitOfSellMeasureStrength != null) {
            lineReq.amountPerFrequencyValue = item.lastUnitOfSellMeasureStrength;
        }
        if (isBlank(lineReq.route)) {
            lineReq.route = item.routeOfAdminTitle;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ResponseMessage(message, null))
                .build();
    }

    private static Integer parseFrequencyUnitInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseDurationUnitInt(String value) {
        return parseFrequencyUnitInt(value);
    }

    private static class BatchAllocation {
        StockBatch batch;
        BigDecimal quantity;
        BigDecimal unitBuy;
        BigDecimal unitSellingPrice;
        BigDecimal lineAmount;
    }

    /**
     * Block OTC debt when the client already owes on visits and is not in a multi-debt credit group.
     * @return error Response to return, or null when allowed
     */
    private Response gateOtcDebtAgainstPriorVisits(org.example.client.domains.Patient patient) {
        if (patient == null || patient.id == null) {
            return null;
        }
        BigDecimal priorVisitDebt = invoiceService.calculateTotalBalanceDueForAllVisits(patient.id);
        BigDecimal accountDue = patient.totalAmountDue != null ? patient.totalAmountDue : BigDecimal.ZERO;
        boolean hasPriorDebt = priorVisitDebt.compareTo(BigDecimal.ZERO) > 0
                || accountDue.compareTo(BigDecimal.ZERO) > 0;
        if (!hasPriorDebt) {
            return null;
        }
        boolean creditAllowed = specialPrivilegeService.patientAllowsCreditDespiteDebt(patient, null);
        if (creditAllowed) {
            return null;
        }
        BigDecimal shown = priorVisitDebt.compareTo(accountDue) >= 0 ? priorVisitDebt : accountDue;
        return badRequest(
                "Client has a debt on one of the visits (Sh:" + shown
                        + "). Clear the debt before giving another, or use a credit-allowed client group.");
    }

    private PatientVisit createOtcCounterVisit(org.example.client.domains.Patient patient) {
        PatientVisitRequest req = new PatientVisitRequest();
        req.visitType = "outpatient";
        req.visitReason = "Over the counter";
        req.allowConcurrentOpen = true;
        PatientVisitDTO dto = patientVisitService.createNewPatientVisit(patient.id, req);
        if (dto == null || dto.id == null) {
            return null;
        }
        return PatientVisit.findById(dto.id);
    }

    private void createOtcDebtTreatment(PatientVisit visit, ResolvedLine r) {
        if (visit == null || r == null || r.quantity == null
                || r.quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal qty = r.quantity.setScale(0, RoundingMode.CEILING);
        TreatmentRequested treatment = new TreatmentRequested();
        treatment.visit = visit;
        treatment.quantity = qty;
        treatment.provisionalQuantity = qty;
        treatment.totalUnits = qty;
        treatment.status = "given";
        treatment.prescribed = true;
        treatment.paid = true;
        treatment.dispensed = true;
        treatment.administered = false;
        treatment.unitSellingPrice = nz(r.unitSellingPrice);
        treatment.unitBuy = nz(r.unitBuy);
        treatment.totalAmount = nz(r.lineAmount);
        treatment.provisionalTotalAmount = treatment.totalAmount;
        if (r.shopItem != null) {
            treatment.itemId = r.shopItem.id;
            treatment.itemName = nzStr(r.shopItem.title);
            treatment.stockBatch = null;
        } else if (r.allocations != null && !r.allocations.isEmpty()) {
            StockBatch batch = r.allocations.get(0).batch;
            if (batch != null) {
                treatment.stockBatch = batch;
                treatment.itemId = batch.stockItemId;
                treatment.itemName = nzStr(batch.stockItemName);
                treatment.shelfNumber = batch.shelfNumber;
                treatment.lastUnitValue = batch.lastUnitValue;
                treatment.availableQuantity = nz(batch.stockAtHand);
                treatment.lastStockAtHand = nz(batch.stockAtHand);
            }
        } else if (r.treatment != null) {
            treatment.itemId = r.treatment.itemId;
            treatment.itemName = r.treatment.itemName;
            treatment.stockBatch = r.treatment.stockBatch;
        } else {
            treatment.itemName = "OTC item";
        }
        if (treatment.itemName == null || treatment.itemName.isBlank()) {
            treatment.itemName = "OTC item";
        }
        treatment.persist();
        r.treatment = treatment;
    }

    private static class ResolvedLine {
        List<BatchAllocation> allocations = new ArrayList<>();
        BigDecimal quantity;
        BigDecimal unitBuy;
        BigDecimal unitSellingPrice;
        BigDecimal lineAmount;
        OtcSaleLineRequest lineRequest;
        TreatmentRequested treatment;
        VisitSundry visitSundry;
        Item shopItem;
        String error;
    }
}
