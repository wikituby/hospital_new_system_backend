package org.example.dashboard.endpoints;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.configuration.handler.ActionMessages;
import org.example.configuration.handler.ResponseMessage;
import org.example.dashboard.services.DashboardService;
import org.example.dashboard.services.payloads.DashboardSummaryDTO;

@Path("Patient-management")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Dashboard", description = "Aggregated dashboard metrics")
public class DashboardController {

    @Inject
    DashboardService dashboardService;

    @GET
    @Path("dashboard-summary")
    @Operation(summary = "Dashboard summary", description = "Returns counts, trends, and small activity lists for the overview dashboard")
    @APIResponse(description = "Successful", responseCode = "200", content = @Content(schema = @Schema(implementation = DashboardSummaryDTO.class)))
    public Response getDashboardSummary(
            @QueryParam("fromDate") String fromDate,
            @QueryParam("toDate") String toDate) {
        LocalDate from = parseDashboardDate(fromDate);
        LocalDate to = parseDashboardDate(toDate);
        DashboardSummaryDTO summary = dashboardService.getSummary(from, to);
        return Response.ok(new ResponseMessage(ActionMessages.FETCHED.label, summary)).build();
    }

    private static LocalDate parseDashboardDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
