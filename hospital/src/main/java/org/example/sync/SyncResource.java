package org.example.sync;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/sync")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SyncResource {

    @Inject
    SyncService syncService;

    @ConfigProperty(name = "sync.api-key", defaultValue = "none")
    String apiKey;

    @GET
    @Path("/pull")
    public Response pull(
            @HeaderParam("X-Sync-Key") String syncKey,
            @QueryParam("deviceId") String deviceId,
            @QueryParam("afterId") Long afterId) {
        Response denied = deny(syncKey);
        if (denied != null) {
            return denied;
        }
        return Response.ok(syncService.pull(deviceId, afterId)).build();
    }

    @POST
    @Path("/push")
    public Response push(
            @HeaderParam("X-Sync-Key") String syncKey,
            Map<String, Object> body) {
        Response denied = deny(syncKey);
        if (denied != null) {
            return denied;
        }
        String deviceId = body == null ? null : text(body.get("deviceId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = body == null ? List.of() : (List<Map<String, Object>>) body.get("events");
        return Response.ok(syncService.push(deviceId, events)).build();
    }

    private Response deny(String syncKey) {
        if (apiKey == null || apiKey.isBlank() || "none".equalsIgnoreCase(apiKey.trim())) {
            return null;
        }
        if (apiKey.equals(syncKey)) {
            return null;
        }
        return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("error", "Sync key required")).build();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
