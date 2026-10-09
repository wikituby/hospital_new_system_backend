package org.example.procedure.itemUsedInProcedure.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.example.configuration.handler.ResponseMessage;
import org.example.inventory.item.domain.Item;
import org.example.procedure.procedure.domains.Procedure;
import org.example.procedure.procedure.domains.ProcedureUnitSellingModel;
import org.example.procedure.itemUsedInProcedure.domains.ItemUsed;
import org.example.procedure.itemUsedInProcedure.domains.repositories.ItemUsedRepository;
import org.example.procedure.itemUsedInProcedure.services.payloads.requests.ItemUsedRequest;
import org.example.procedure.itemUsedInProcedure.services.payloads.responses.ItemUsedDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ItemUsedService {

    @Inject
    ItemUsedRepository itemUsedRepository;

    private static final String NOT_FOUND = "Not found!";

    @Transactional
    public ItemUsedDTO addItemUsed(ItemUsedRequest request) {
        Procedure procedure = Procedure.findById(request.procedureId);
        if (procedure == null) {
            throw new WebApplicationException("Procedure not found", Response.Status.NOT_FOUND);
        }
        String feeName = request.feeName == null ? "" : request.feeName.trim();
        boolean isFee = !feeName.isEmpty() || "FEE".equalsIgnoreCase(request.lineType);
        Item item = null;
        if (!isFee) {
            item = request.itemId == null ? null : Item.findById(request.itemId);
            if (item == null) {
                throw new WebApplicationException("Item or Procedure not found", Response.Status.NOT_FOUND);
            }
        } else if (feeName.isEmpty()) {
            throw new WebApplicationException("Fee name is required", Response.Status.BAD_REQUEST);
        }

        ProcedureUnitSellingModel sellingModel = null;
        if (request.unitSellingModelId != null) {
            sellingModel = ProcedureUnitSellingModel.findById(request.unitSellingModelId);
            if (sellingModel == null || sellingModel.procedure == null || !procedure.id.equals(sellingModel.procedure.id)) {
                throw new WebApplicationException("Unit sell model not found for this service", Response.Status.NOT_FOUND);
            }
        }

        ItemUsed existing = isFee
                ? this.findFee(request.procedureId, request.unitSellingModelId, feeName)
                : (request.unitSellingModelId != null
                ? ItemUsed.find(
                        "procedureId = ?1 and itemId = ?2 and unitSellingModelId = ?3",
                        request.procedureId,
                        request.itemId,
                        request.unitSellingModelId
                ).firstResult()
                : ItemUsed.find(
                        "procedureId = ?1 and itemId = ?2 and unitSellingModelId is null",
                        request.procedureId,
                        request.itemId
                ).firstResult());

        boolean exclude = Boolean.TRUE.equals(request.excluded);
        if (exclude && sellingModel == null) {
            throw new WebApplicationException("Only a unit sell model can leave out a service item", Response.Status.BAD_REQUEST);
        }

        ItemUsed itemUsed = existing != null ? existing : new ItemUsed();
        itemUsed.procedureName = procedure.procedureName;
        itemUsed.itemName = isFee ? feeName : item.title;
        itemUsed.procedureId = procedure.id;
        itemUsed.unitSellingModelId = sellingModel != null ? sellingModel.id : null;
        itemUsed.itemId = isFee ? null : item.id;
        itemUsed.lineType = isFee ? "FEE" : "ITEM";
        if (exclude) {
            itemUsed.excluded = true;
            itemUsed.quantityUsed = BigDecimal.ZERO;
            itemUsed.unitCostPrice = BigDecimal.ZERO;
        } else {
            itemUsed.excluded = false;
            itemUsed.quantityUsed = BigDecimal.valueOf(request.quantityUsed);
            itemUsed.unitCostPrice = request.unitCostPrice != null
                    ? request.unitCostPrice
                    : (!isFee && item.costPrice != null ? item.costPrice : BigDecimal.ZERO);
        }
        itemUsed.persist();
        if (itemUsed.unitSellingModelId != null) {
            this.refreshModelUnitCost(itemUsed.unitSellingModelId);
        } else {
            this.refreshAfterServiceItemsChange(procedure.id);
        }

        return new ItemUsedDTO(itemUsed);

    }


    public List<ItemUsedDTO> getItemsUsedByProcedure(Long procedureId) {
        return this.getItemsUsedByProcedure(procedureId, null);
    }

    public List<ItemUsedDTO> getItemsUsedByProcedure(Long procedureId, Long unitSellingModelId) {
        if (unitSellingModelId == null) {
            List<ItemUsed> defaults = ItemUsed
                    .find("procedureId = ?1 and unitSellingModelId is null ORDER BY id DESC", procedureId)
                    .list();
            return defaults.stream().map(row -> {
                ItemUsedDTO dto = new ItemUsedDTO(row);
                dto.fromServiceDefault = true;
                return dto;
            }).collect(Collectors.toList());
        }
        return this.mergedModelItems(procedureId, unitSellingModelId);
    }

    private ItemUsed findFee(Long procedureId, Long unitSellingModelId, String feeName) {
        String key = feeName.trim().toLowerCase();
        List<ItemUsed> rows = unitSellingModelId != null
                ? ItemUsed.list("procedureId = ?1 and unitSellingModelId = ?2", procedureId, unitSellingModelId)
                : ItemUsed.list("procedureId = ?1 and unitSellingModelId is null", procedureId);
        for (ItemUsed row : rows) {
            if (!"FEE".equalsIgnoreCase(row.lineType) || row.itemName == null) {
                continue;
            }
            if (row.itemName.trim().toLowerCase().equals(key)) {
                return row;
            }
        }
        return null;
    }

    private String lineKey(ItemUsed row) {
        if (row == null) {
            return null;
        }
        if ("FEE".equalsIgnoreCase(row.lineType)) {
            if (row.itemName == null || row.itemName.trim().isEmpty()) {
                return null;
            }
            return "fee:" + row.itemName.trim().toLowerCase();
        }
        if (row.itemId == null) {
            return null;
        }
        return "item:" + row.itemId;
    }

    /** Service items, with this model's own edits and extra items applied. */
    private List<ItemUsedDTO> mergedModelItems(Long procedureId, Long unitSellingModelId) {
        List<ItemUsed> defaults = ItemUsed.list("procedureId = ?1 and unitSellingModelId is null", procedureId);
        List<ItemUsed> own = ItemUsed.list(
                "procedureId = ?1 and unitSellingModelId = ?2",
                procedureId,
                unitSellingModelId
        );
        Map<String, ItemUsed> ownByKey = new LinkedHashMap<>();
        for (ItemUsed row : own) {
            String key = this.lineKey(row);
            if (key != null) {
                ownByKey.put(key, row);
            }
        }
        List<ItemUsedDTO> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ItemUsed base : defaults) {
            String key = this.lineKey(base);
            if (key == null) {
                continue;
            }
            seen.add(key);
            ItemUsed custom = ownByKey.get(key);
            if (custom != null && Boolean.TRUE.equals(custom.excluded)) {
                continue;
            }
            if (custom != null) {
                ItemUsedDTO dto = new ItemUsedDTO(custom);
                dto.fromServiceDefault = true;
                dto.overridden = true;
                result.add(dto);
            } else {
                ItemUsedDTO dto = new ItemUsedDTO(base);
                dto.fromServiceDefault = true;
                dto.overridden = false;
                result.add(dto);
            }
        }
        for (ItemUsed extra : own) {
            String key = this.lineKey(extra);
            if (key == null || seen.contains(key) || Boolean.TRUE.equals(extra.excluded)) {
                continue;
            }
            ItemUsedDTO dto = new ItemUsedDTO(extra);
            dto.fromServiceDefault = false;
            result.add(dto);
        }
        return result;
    }



    @Transactional
    public void performProcedure(Long procedureId) {
        Procedure procedure = Procedure.findById(procedureId);
        if (procedure == null) {
            //throw new IllegalArgumentException("Procedure not found.");
            throw new WebApplicationException("Procedure not found",409);


        }

        List<ItemUsed> usedItems = ItemUsed.list("procedureId", procedure.id);

        for (ItemUsed usage : usedItems) {
            if (usage.itemId == null || "FEE".equalsIgnoreCase(usage.lineType)) {
                continue;
            }
            Item item = Item.findById(usage.itemId);

            if (item == null) {
               // throw new IllegalStateException("Item not found for usage record.");
                throw new WebApplicationException("Item not found for usage record",409);

            }

            if (item.stockAtHand.compareTo(usage.quantityUsed) < 0) {
                //throw new IllegalStateException("Not enough stock for item: " + item.title);
                throw new WebApplicationException("Not enough stock for item: " + item.title);

            }

            item.stockAtHand = item.stockAtHand.subtract(usage.quantityUsed);
            item.persist();
        }

    }


    @Transactional
    public void restoreStockOnProcedureDelete(Long procedureId) {
        Procedure procedure = Procedure.findById(procedureId);
        if (procedure == null) {
            throw new WebApplicationException("procedure not found for usage record",409);

        }

        List<ItemUsed> usedItems = ItemUsed.list("procedureId", procedure.id);

        if (usedItems.isEmpty()) {
            return;
        }


        for (ItemUsed usage : usedItems) {
            if (usage.itemId == null || "FEE".equalsIgnoreCase(usage.lineType)) {
                continue;
            }
            Item item = Item.findById(usage.itemId);

            if (item == null) {
                continue;

            }

            // Add back the used quantity
            item.stockAtHand = item.stockAtHand.add(usage.quantityUsed);
            item.persist();
        }

    }



    public List<ItemUsedDTO> getCart(Long labTestId) {
        // Fetch the list of ItemUsed entities by labTestId
        List<ItemUsed> itemUsedList = ItemUsed.find("labTest.id", labTestId).list();

        // Map the ItemUsed entities to ItemUsedDTOs using a method reference
        return itemUsedList.stream()
                .map(this::mapToDTO) // Replace lambda with method reference
                .collect(Collectors.toList()); // Collect the DTOs into a list
    }

    // Method to map ItemUsed entity to ItemUsedDTO
    private ItemUsedDTO mapToDTO(ItemUsed itemUsed) {
        return new ItemUsedDTO(itemUsed);
    }

    @Transactional
    public Response deleteItemUsed(Long id){
        ItemUsed itemUsed = ItemUsed.findById(id); // correct: call on your entity

        if (itemUsed == null) {
            //throw new WebApplicationException("ItemUsed with id " + id + " not found", 404);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ResponseMessage("ItemUsed with id" + " " + id + " " +"not found"))
                    .build();
        }

        Long procedureId = itemUsed.procedureId;
        Long modelId = itemUsed.unitSellingModelId;
        itemUsed.delete();
        if (modelId != null) {
            this.refreshModelUnitCost(modelId);
        } else {
            this.refreshAfterServiceItemsChange(procedureId);
        }

        return Response.ok(new ResponseMessage("Item Used Deleted Successfully")).build();
    }

    /**
     * The model's cost is the total of its items. Unit sell stays at cost plus the model's profit,
     * and the bundle price follows that unit sell.
     */
    private void refreshModelUnitCost(Long modelId) {
        ProcedureUnitSellingModel model = modelId == null ? null : ProcedureUnitSellingModel.findById(modelId);
        if (model == null || model.procedure == null) {
            return;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (ItemUsedDTO row : this.mergedModelItems(model.procedure.id, modelId)) {
            BigDecimal qty = row.quantityUsed != null ? row.quantityUsed : BigDecimal.ZERO;
            BigDecimal unit = row.unitCostPrice != null ? row.unitCostPrice : BigDecimal.ZERO;
            total = total.add(qty.multiply(unit));
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        model.unitCostPrice = total;
        BigDecimal profit = model.profitMargin != null ? model.profitMargin : BigDecimal.ZERO;
        int units = model.unitsInBundle != null && model.unitsInBundle > 0 ? model.unitsInBundle : 1;
        BigDecimal unitSell = total.add(profit).setScale(2, RoundingMode.HALF_UP);
        model.unitsInBundle = units;
        model.unitSellingPrice = unitSell;
        model.bundlePrice = unitSell.multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.HALF_UP);
        model.persist();

        Procedure procedure = model.procedure;
        boolean selected = model.id.equals(procedure.unitSellingModelId);
        boolean fallback = procedure.unitSellingModelId == null && Boolean.TRUE.equals(model.isDefault);
        if (selected || fallback) {
            procedure.unitCostPrice = total;
            procedure.unitSellingPrice = unitSell;
            if (procedure.unitSellingModelId == null) {
                procedure.unitSellingModelId = model.id;
            }
            procedure.persist();
        }
    }

    /** Service-item changes flow into every unit sell model that still uses those defaults. */
    private void refreshAfterServiceItemsChange(Long procedureId) {
        if (procedureId == null) {
            return;
        }
        List<ProcedureUnitSellingModel> models = ProcedureUnitSellingModel.list("procedure.id", procedureId);
        if (models.isEmpty()) {
            this.refreshProcedureUnitCost(procedureId);
            return;
        }
        for (ProcedureUnitSellingModel model : models) {
            this.refreshModelUnitCost(model.id);
        }
    }

    /** Service cost is the sum of items used (quantity × unit cost). */
    private void refreshProcedureUnitCost(Long procedureId) {
        if (procedureId == null) {
            return;
        }
        Procedure procedure = Procedure.findById(procedureId);
        if (procedure == null) {
            return;
        }
        List<ItemUsed> rows = ItemUsed.list("procedureId = ?1 and unitSellingModelId is null", procedureId);
        BigDecimal total = BigDecimal.ZERO;
        for (ItemUsed row : rows) {
            BigDecimal qty = row.quantityUsed != null ? row.quantityUsed : BigDecimal.ZERO;
            BigDecimal unit = row.unitCostPrice != null ? row.unitCostPrice : BigDecimal.ZERO;
            total = total.add(qty.multiply(unit));
        }
        procedure.unitCostPrice = total.setScale(2, RoundingMode.HALF_UP);
        procedure.persist();
    }






}




