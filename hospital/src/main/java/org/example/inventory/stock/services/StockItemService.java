package org.example.inventory.stock.services;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.example.configuration.handler.ResponseMessage;
import org.example.inventory.item.domain.*;
import org.example.inventory.item.domain.repositories.*;
import org.example.inventory.stock.domains.StockBatch;
import org.example.inventory.stock.domains.StockItem;
import org.example.inventory.stock.domains.StockItemBarcode;
import org.example.inventory.stock.domains.StockItemIndication;
import org.example.inventory.stock.domains.repositories.StockBatchRepository;
import org.example.inventory.stock.domains.repositories.StockItemBarcodeRepository;
import org.example.inventory.stock.domains.repositories.StockItemIndicationRepository;
import org.example.inventory.stock.domains.repositories.StockItemRepository;
import org.example.inventory.stock.domains.repositories.StockReceiveRepository;
import org.example.inventory.stock.services.payloads.requests.StockItemBarcodeRequest;
import org.example.inventory.stock.services.payloads.requests.StockItemIndicationRequest;
import org.example.inventory.stock.services.payloads.requests.StockItemIngredientRequest;
import org.example.inventory.stock.services.payloads.requests.StockItemRequest;
import org.example.inventory.stock.services.payloads.responses.dtos.StockItemBarcodeDTO;
import org.example.inventory.stock.services.payloads.responses.dtos.StockItemDTO;
import org.example.inventory.stock.services.payloads.responses.dtos.StockItemIndicationDTO;
import org.example.inventory.store.domains.repositories.StoreRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class StockItemService {

    @Inject
    StockItemRepository stockItemRepository;

    @Inject
    ActiveIngredientRepository activeIngredientRepository;

    @Inject
    StoreRepository storeRepository;

    @Inject
    BrandRepository brandRepository;
    
    @Inject
    CompositionRepository compositionRepository;
    
    @Inject
    StrengthRepository strengthRepository;
    
    @Inject
    FormulationRepository formulationRepository;
    
    @Inject
    LowestPackageRepository lowestPackageRepository;
    
    @Inject
    RouteOfAdminRepository routeOfAdminRepository;
    
    @Inject
    StrengthUnitRepository strengthUnitRepository;
    
    @Inject
    CategoryRepository categoryRepository;

    @Inject
    StockBatchRepository stockBatchRepository;

    @Inject
    StockReceiveRepository stockReceiveRepository;

    @Inject
    StockItemIndicationRepository stockItemIndicationRepository;

    @Inject
    StockItemBarcodeRepository stockItemBarcodeRepository;

    /**
     * Create or update a StockItem
     */
    @Transactional
    public Response createOrUpdateStockItem(StockItemRequest request) {
        try {
            String resolvedStockItemName = resolveStockItemName(request);
            Response duplicate = duplicateStockItemResponse(request, null);
            if (duplicate != null) {
                return duplicate;
            }
            String generatedStockItemName = resolvedStockItemName;
            
            // 🔍 Check if item with same generated name and brand exists
            StockItem existing = stockItemRepository.find(
                    "stockItemName = ?1 and brandId = ?2",
                    generatedStockItemName, request.brandId
            ).firstResult();

            if (existing == null) {
                // 🆕 Create new StockItem
                StockItem stockItem = new StockItem();
                stockItem.stockItemName = generatedStockItemName;
                stockItem.brandId = request.brandId;
                
                // Set brandName from brand repository
                if (request.brandId != null) {
                    Brand brand = brandRepository.findById(request.brandId);
                    if (brand != null && brand.name != null) {
                        stockItem.brandName = brand.name;
                    }
                }
                
                stockItem.formulationId = request.formulationId;
                // Set formulationName from formulation repository
                if (request.formulationId != null) {
                    Formulation formulation = formulationRepository.findById(request.formulationId);
                    if (formulation != null && formulation.name != null) {
                        stockItem.formulationName = formulation.name;
                    }
                }
                
                stockItem.lowestPackageId = request.lowestPackageId;
                // Set lowestPackageTitle from lowestPackage repository
                if (request.lowestPackageId != null) {
                    LowestPackage lowestPackage = lowestPackageRepository.findById(request.lowestPackageId);
                    if (lowestPackage != null && lowestPackage.title != null) {
                        stockItem.lowestPackageTitle = lowestPackage.title;
                    }
                }
                
                stockItem.totalCompositionValue = request.totalCompositionValue;
                stockItem.totalCompositionUnitId = request.totalCompositionUnitId;
                // Set totalCompositionUnitTitle from strengthUnit repository
                if (request.totalCompositionUnitId != null) {
                    StrengthUnit unit = strengthUnitRepository.findById(request.totalCompositionUnitId);
                    if (unit != null && unit.title != null) {
                        stockItem.totalCompositionUnitTitle = unit.title;
                    }
                }
                
                stockItem.reconstitutionValue = request.reconstitutionValue;
                stockItem.reconstitutionUnitId = request.reconstitutionUnitId;
                // Set reconstitutionUnitTitle from strengthUnit repository
                if (request.reconstitutionUnitId != null) {
                    StrengthUnit unit = strengthUnitRepository.findById(request.reconstitutionUnitId);
                    if (unit != null && unit.title != null) {
                        stockItem.reconstitutionUnitTitle = unit.title;
                    }
                }
                
                stockItem.itemCategoryId = request.itemCategoryId;
                // Set itemCategoryName from category repository
                if (request.itemCategoryId != null) {
                    Category category = categoryRepository.findById(request.itemCategoryId);
                    if (category != null && category.name != null) {
                        stockItem.itemCategoryName = category.name;
                    }
                }
                
                stockItem.routeOfAdminId = request.routeOfAdminId;
                // Set routeOfAdminTitle from routeOfAdmin repository
                if (request.routeOfAdminId != null) {
                    RouteOfAdmin routeOfAdmin = routeOfAdminRepository.findById(request.routeOfAdminId);
                    if (routeOfAdmin != null && routeOfAdmin.title != null) {
                        stockItem.routeOfAdminTitle = routeOfAdmin.title;
                    }
                }
                
                // Updated fields by the stock batch service
                stockItem.lastUnitOfSellMeasure = request.lastUnitOfSellMeasure;
                stockItem.lastUnitOfSellMeasureStrength = request.lastUnitOfSellMeasureStrength;
                stockItem.lastUnitOfSellMeasureStrengthUnit = request.lastUnitOfSellMeasureStrengthUnit;
                // Set lastUnitOfSellMeasureStrengthUnitTitle from strengthUnit repository
                if (request.lastUnitOfSellMeasureStrengthUnit != null) {
                    StrengthUnit unit = strengthUnitRepository.findById(request.lastUnitOfSellMeasureStrengthUnit);
                    if (unit != null && unit.title != null) {
                        stockItem.lastUnitOfSellMeasureStrengthUnitTitle = unit.title;
                    }
                }
                
                applyPrescribingMeasureFields(stockItem, request);
                
                // Others - medical information fields
                stockItem.indication = request.indication;
                stockItem.image = request.image;
                stockItem.contraIndication = request.contraIndication;
                stockItem.drugIteractions = request.drugIteractions;
                stockItem.description = request.description;
                stockItem.pharmacodynamics = request.pharmacodynamics;
                stockItem.pharmacokinetics = request.pharmacokinetics;
                stockItem.adverseEffects = request.adverseEffects;
                stockItem.dosage = request.dosage;
                stockItem.notes = request.notes;
                stockItem.methodOfReconstitution = request.methodOfReconstitution;
                stockItem.descriptionBeforeReconstitution = request.descriptionBeforeReconstitution;
                stockItem.descriptionAfterReconstitution = request.descriptionAfterReconstitution;
                stockItem.storage = request.storage;
                stockItem.antiDote = request.antiDote;
                
                stockItem.creationDate = LocalDate.now();
                stockItem.updateDate = LocalDate.now();

                stockItem.persist();
                applyPrimaryBarcode(stockItem, request.barcode);
                
                // Save ingredients/compositions
                saveCompositions(stockItem.id, request.ingredients);
                syncIndications(stockItem, request);
                syncOtherBarcodes(stockItem, request);
                
                return Response.ok(new ResponseMessage("Stock item created successfully", toStockItemDTO(stockItem))).build();
            } else {
                // 🔄 Update existing StockItem
                existing.stockItemName = generatedStockItemName;
                existing.brandId = request.brandId;
                
                // Set brandName from brand repository
                if (request.brandId != null) {
                    Brand brand = brandRepository.findById(request.brandId);
                    if (brand != null && brand.name != null) {
                        existing.brandName = brand.name;
                    }
                }

                existing.formulationId = request.formulationId;
                // Set formulationName from formulation repository
                if (request.formulationId != null) {
                    Formulation formulation = formulationRepository.findById(request.formulationId);
                    if (formulation != null && formulation.name != null) {
                        existing.formulationName = formulation.name;
                    }
                }
                
                existing.lowestPackageId = request.lowestPackageId;
                // Set lowestPackageTitle from lowestPackage repository
                if (request.lowestPackageId != null) {
                    LowestPackage lowestPackage = lowestPackageRepository.findById(request.lowestPackageId);
                    if (lowestPackage != null && lowestPackage.title != null) {
                        existing.lowestPackageTitle = lowestPackage.title;
                    }
                }
                
                existing.totalCompositionValue = request.totalCompositionValue;
                existing.totalCompositionUnitId = request.totalCompositionUnitId;
                // Set totalCompositionUnitTitle from strengthUnit repository
                if (request.totalCompositionUnitId != null) {
                    StrengthUnit unit = strengthUnitRepository.findById(request.totalCompositionUnitId);
                    if (unit != null && unit.title != null) {
                        existing.totalCompositionUnitTitle = unit.title;
                    }
                }
                
                existing.reconstitutionValue = request.reconstitutionValue;
                existing.reconstitutionUnitId = request.reconstitutionUnitId;
                // Set reconstitutionUnitTitle from strengthUnit repository
                if (request.reconstitutionUnitId != null) {
                    StrengthUnit unit = strengthUnitRepository.findById(request.reconstitutionUnitId);
                    if (unit != null && unit.title != null) {
                        existing.reconstitutionUnitTitle = unit.title;
                    }
                }
                
                existing.itemCategoryId = request.itemCategoryId;
                // Set itemCategoryName from category repository
                if (request.itemCategoryId != null) {
                    Category category = categoryRepository.findById(request.itemCategoryId);
                    if (category != null && category.name != null) {
                        existing.itemCategoryName = category.name;
                    }
                }
                
                existing.routeOfAdminId = request.routeOfAdminId;
                // Set routeOfAdminTitle from routeOfAdmin repository
                if (request.routeOfAdminId != null) {
                    RouteOfAdmin routeOfAdmin = routeOfAdminRepository.findById(request.routeOfAdminId);
                    if (routeOfAdmin != null && routeOfAdmin.title != null) {
                        existing.routeOfAdminTitle = routeOfAdmin.title;
                    }
                }
                
                // Updated fields by the stock batch service
                existing.lastUnitOfSellMeasure = request.lastUnitOfSellMeasure;
                existing.lastUnitOfSellMeasureStrength = request.lastUnitOfSellMeasureStrength;
                existing.lastUnitOfSellMeasureStrengthUnit = request.lastUnitOfSellMeasureStrengthUnit;
                // Set lastUnitOfSellMeasureStrengthUnitTitle from strengthUnit repository
                if (request.lastUnitOfSellMeasureStrengthUnit != null) {
                    StrengthUnit unit = strengthUnitRepository.findById(request.lastUnitOfSellMeasureStrengthUnit);
                    if (unit != null && unit.title != null) {
                        existing.lastUnitOfSellMeasureStrengthUnitTitle = unit.title;
                    }
                }
                
                applyPrescribingMeasureFields(existing, request);
                
                // Others - medical information fields
                existing.indication = request.indication;
                existing.image = request.image;
                existing.contraIndication = request.contraIndication;
                existing.drugIteractions = request.drugIteractions;
                existing.description = request.description;
                existing.pharmacodynamics = request.pharmacodynamics;
                existing.pharmacokinetics = request.pharmacokinetics;
                existing.adverseEffects = request.adverseEffects;
                existing.dosage = request.dosage;
                existing.notes = request.notes;
                existing.methodOfReconstitution = request.methodOfReconstitution;
                existing.descriptionBeforeReconstitution = request.descriptionBeforeReconstitution;
                existing.descriptionAfterReconstitution = request.descriptionAfterReconstitution;
                existing.storage = request.storage;
                existing.antiDote = request.antiDote;
                
                existing.updateDate = LocalDate.now();

                stockItemRepository.persist(existing);
                applyPrimaryBarcode(existing, request.barcode);
                syncStockItemNameToBatchesAndReceives(existing.id, existing.stockItemName);
                
                // Delete existing compositions and save new ones
                deleteCompositionsByStockItemId(existing.id);
                saveCompositions(existing.id, request.ingredients);
                syncIndications(existing, request);
                syncOtherBarcodes(existing, request);
                
                return Response.ok(new ResponseMessage("Stock item updated successfully", toStockItemDTO(existing))).build();
            }

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ResponseMessage("Error while saving StockItem: " + e.getMessage(), null))
                    .build();
        }
    }

    /**
     * List all StockItems
     */
    @Transactional
    public List<StockItemDTO> getAllStockItems() {
        CategoryLookup categoryLookup = CategoryLookup.from(categoryRepository.listAll());
        Map<Long, String> compositionKeys = compositionKeysByStockItem();
        return stockItemRepository.listAll(Sort.descending("id"))
                .stream()
                .map(item -> {
                    StockItemDTO dto = toStockItemDTO(item, categoryLookup);
                    dto.compositionKey = compositionKeys.get(item.id);
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /**
     * Find StockItem by ID
     */
    @Transactional
    public Response getStockItemById(Long id) {
        StockItem stockItem = stockItemRepository.findById(id);
        if (stockItem == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ResponseMessage("StockItem not found with ID: " + id, null))
                    .build();
        }
        return Response.ok(new ResponseMessage("StockItem found", toStockItemDTO(stockItem))).build();
    }

    /**
     * Update StockItem by ID
     */
    @Transactional
    public Response updateStockItem(Long id, StockItemRequest request) {
        try {
            StockItem stockItem = stockItemRepository.findById(id);
            if (stockItem == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(new ResponseMessage("StockItem not found with ID: " + id, null))
                        .build();
            }

            String resolvedStockItemName = resolveStockItemName(request);
            Response duplicate = duplicateStockItemResponse(request, id);
            if (duplicate != null) {
                return duplicate;
            }
            stockItem.stockItemName = resolvedStockItemName;
            stockItem.brandId = request.brandId;
            
            // Set brandName from brand repository
            if (request.brandId != null) {
                Brand brand = brandRepository.findById(request.brandId);
                if (brand != null && brand.name != null) {
                    stockItem.brandName = brand.name;
                }
            }

            stockItem.formulationId = request.formulationId;
            // Set formulationName from formulation repository
            if (request.formulationId != null) {
                Formulation formulation = formulationRepository.findById(request.formulationId);
                if (formulation != null && formulation.name != null) {
                    stockItem.formulationName = formulation.name;
                }
            }
            
            stockItem.lowestPackageId = request.lowestPackageId;
            // Set lowestPackageTitle from lowestPackage repository
            if (request.lowestPackageId != null) {
                LowestPackage lowestPackage = lowestPackageRepository.findById(request.lowestPackageId);
                if (lowestPackage != null && lowestPackage.title != null) {
                    stockItem.lowestPackageTitle = lowestPackage.title;
                }
            }
            
            stockItem.totalCompositionValue = request.totalCompositionValue;
            stockItem.totalCompositionUnitId = request.totalCompositionUnitId;
            // Set totalCompositionUnitTitle from strengthUnit repository
            if (request.totalCompositionUnitId != null) {
                StrengthUnit unit = strengthUnitRepository.findById(request.totalCompositionUnitId);
                if (unit != null && unit.title != null) {
                    stockItem.totalCompositionUnitTitle = unit.title;
                }
            }
            
            stockItem.reconstitutionValue = request.reconstitutionValue;
            stockItem.reconstitutionUnitId = request.reconstitutionUnitId;
            // Set reconstitutionUnitTitle from strengthUnit repository
            if (request.reconstitutionUnitId != null) {
                StrengthUnit unit = strengthUnitRepository.findById(request.reconstitutionUnitId);
                if (unit != null && unit.title != null) {
                    stockItem.reconstitutionUnitTitle = unit.title;
                }
            }
            
            stockItem.itemCategoryId = request.itemCategoryId;
            // Set itemCategoryName from category repository
            if (request.itemCategoryId != null) {
                Category category = categoryRepository.findById(request.itemCategoryId);
                if (category != null && category.name != null) {
                    stockItem.itemCategoryName = category.name;
                }
            }
            
            stockItem.routeOfAdminId = request.routeOfAdminId;
            // Set routeOfAdminTitle from routeOfAdmin repository
            if (request.routeOfAdminId != null) {
                RouteOfAdmin routeOfAdmin = routeOfAdminRepository.findById(request.routeOfAdminId);
                if (routeOfAdmin != null && routeOfAdmin.title != null) {
                    stockItem.routeOfAdminTitle = routeOfAdmin.title;
                }
            }
            
            // Updated fields by the stock batch service
            stockItem.lastUnitOfSellMeasure = request.lastUnitOfSellMeasure;
            stockItem.lastUnitOfSellMeasureStrength = request.lastUnitOfSellMeasureStrength;
            stockItem.lastUnitOfSellMeasureStrengthUnit = request.lastUnitOfSellMeasureStrengthUnit;
            // Set lastUnitOfSellMeasureStrengthUnitTitle from strengthUnit repository
            if (request.lastUnitOfSellMeasureStrengthUnit != null) {
                StrengthUnit unit = strengthUnitRepository.findById(request.lastUnitOfSellMeasureStrengthUnit);
                if (unit != null && unit.title != null) {
                    stockItem.lastUnitOfSellMeasureStrengthUnitTitle = unit.title;
                }
            }
            
            applyPrescribingMeasureFields(stockItem, request);
            
            // Others - medical information fields
            stockItem.indication = request.indication;
            stockItem.image = request.image;
            stockItem.contraIndication = request.contraIndication;
            stockItem.drugIteractions = request.drugIteractions;
            stockItem.description = request.description;
            stockItem.pharmacodynamics = request.pharmacodynamics;
            stockItem.pharmacokinetics = request.pharmacokinetics;
            stockItem.adverseEffects = request.adverseEffects;
            stockItem.dosage = request.dosage;
            stockItem.notes = request.notes;
            stockItem.methodOfReconstitution = request.methodOfReconstitution;
            stockItem.descriptionBeforeReconstitution = request.descriptionBeforeReconstitution;
            stockItem.descriptionAfterReconstitution = request.descriptionAfterReconstitution;
            stockItem.storage = request.storage;
            stockItem.antiDote = request.antiDote;
            
            stockItem.updateDate = LocalDate.now();

            stockItemRepository.persist(stockItem);
            applyPrimaryBarcode(stockItem, request.barcode);
            syncStockItemNameToBatchesAndReceives(stockItem.id, stockItem.stockItemName);
            
            // Delete existing compositions and save new ones
            deleteCompositionsByStockItemId(stockItem.id);
            saveCompositions(stockItem.id, request.ingredients);
            syncIndications(stockItem, request);
            syncOtherBarcodes(stockItem, request);
            
            return Response.ok(new ResponseMessage("Stock item updated successfully", toStockItemDTO(stockItem))).build();
            
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ResponseMessage("Error while updating StockItem: " + e.getMessage(), null))
                    .build();
        }
    }

    /**
     * Delete StockItem by ID
     */
    @Transactional
    public Response deleteStockItem(Long id) {
        deleteOtherBarcodes(id);
        deleteIndicationsByStockItemId(id);
        boolean deleted = stockItemRepository.deleteById(id);
        if (deleted) {
            return Response.ok(new ResponseMessage("StockItem deleted successfully")).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ResponseMessage("StockItem not found with ID: " + id, null))
                    .build();
        }
    }

    /**
     * Update missing names and titles for existing StockItems
     * This method populates name/title fields for StockItems that have IDs but missing names/titles
     */
    @Transactional
    public Response updateMissingNamesAndTitles() {
        try {
            List<StockItem> allStockItems = stockItemRepository.listAll();
            int updatedCount = 0;
            int totalCount = allStockItems.size();

            for (StockItem stockItem : allStockItems) {
                boolean wasUpdated = false;

                // Update brandName
                if (stockItem.brandId != null && (stockItem.brandName == null || stockItem.brandName.trim().isEmpty())) {
                    Brand brand = brandRepository.findById(stockItem.brandId);
                    if (brand != null && brand.name != null) {
                        stockItem.brandName = brand.name;
                        wasUpdated = true;
                    }
                }

                // Update formulationName
                if (stockItem.formulationId != null && (stockItem.formulationName == null || stockItem.formulationName.trim().isEmpty())) {
                    Formulation formulation = formulationRepository.findById(stockItem.formulationId);
                    if (formulation != null && formulation.name != null) {
                        stockItem.formulationName = formulation.name;
                        wasUpdated = true;
                    }
                }

                // Update lowestPackageTitle
                if (stockItem.lowestPackageId != null && (stockItem.lowestPackageTitle == null || stockItem.lowestPackageTitle.trim().isEmpty())) {
                    LowestPackage lowestPackage = lowestPackageRepository.findById(stockItem.lowestPackageId);
                    if (lowestPackage != null && lowestPackage.title != null) {
                        stockItem.lowestPackageTitle = lowestPackage.title;
                        wasUpdated = true;
                    }
                }

                // Update totalCompositionUnitTitle
                if (stockItem.totalCompositionUnitId != null && (stockItem.totalCompositionUnitTitle == null || stockItem.totalCompositionUnitTitle.trim().isEmpty())) {
                    StrengthUnit unit = strengthUnitRepository.findById(stockItem.totalCompositionUnitId);
                    if (unit != null && unit.title != null) {
                        stockItem.totalCompositionUnitTitle = unit.title;
                        wasUpdated = true;
                    }
                }

                // Update reconstitutionUnitTitle
                if (stockItem.reconstitutionUnitId != null && (stockItem.reconstitutionUnitTitle == null || stockItem.reconstitutionUnitTitle.trim().isEmpty())) {
                    StrengthUnit unit = strengthUnitRepository.findById(stockItem.reconstitutionUnitId);
                    if (unit != null && unit.title != null) {
                        stockItem.reconstitutionUnitTitle = unit.title;
                        wasUpdated = true;
                    }
                }

                // Update itemCategoryName
                if (stockItem.itemCategoryId != null && (stockItem.itemCategoryName == null || stockItem.itemCategoryName.trim().isEmpty())) {
                    Category category = categoryRepository.findById(stockItem.itemCategoryId);
                    if (category != null && category.name != null) {
                        stockItem.itemCategoryName = category.name;
                        wasUpdated = true;
                    }
                }

                // Update routeOfAdminTitle
                if (stockItem.routeOfAdminId != null && (stockItem.routeOfAdminTitle == null || stockItem.routeOfAdminTitle.trim().isEmpty())) {
                    RouteOfAdmin routeOfAdmin = routeOfAdminRepository.findById(stockItem.routeOfAdminId);
                    if (routeOfAdmin != null && routeOfAdmin.title != null) {
                        stockItem.routeOfAdminTitle = routeOfAdmin.title;
                        wasUpdated = true;
                    }
                }

                if (wasUpdated) {
                    stockItem.updateDate = LocalDate.now();
                    stockItemRepository.persist(stockItem);
                    updatedCount++;
                }
            }

            String message = String.format("Update completed. %d out of %d stock items were updated with missing names/titles.", updatedCount, totalCount);
            return Response.ok(new ResponseMessage(message, null)).build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ResponseMessage("Error while updating StockItem names/titles: " + e.getMessage(), null))
                    .build();
        }
    }
    
    /**
     * Save compositions for a StockItem
     * Creates Strength records and links them via Composition records
     */
    private void saveCompositions(Long stockItemId, List<StockItemIngredientRequest> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) {
            return;
        }
        
        for (StockItemIngredientRequest ingredient : ingredients) {
            Long activeIngredientPk = resolveActiveIngredientId(ingredient);
            if (activeIngredientPk == null || ingredient.strength == null || ingredient.unitId == null) {
                continue; // Skip invalid ingredients
            }
            
            // Create or find existing Strength record
            Strength strength = strengthRepository.find(
                "activeIngredientId = ?1 and strengthValue = ?2 and strengthUnitId = ?3",
                activeIngredientPk, ingredient.strength, ingredient.unitId
            ).firstResult();
            
            if (strength == null) {
                // Create new Strength
                strength = new Strength();
                strength.activeIngredientId = activeIngredientPk;
                strength.strengthValue = ingredient.strength;
                strength.strengthUnitId = ingredient.unitId;
                strengthRepository.persist(strength);
            }
            
            // Create Composition linking StockItem to Strength
            Composition composition = new Composition();
            composition.stockItemId = stockItemId;
            composition.strengthId = strength.id;
            compositionRepository.persist(composition);
        }
    }
    
    /**
     * Delete all compositions for a StockItem
     */
    private void deleteCompositionsByStockItemId(Long stockItemId) {
        List<Composition> compositions = compositionRepository.find("stockItemId", stockItemId).list();
        for (Composition composition : compositions) {
            compositionRepository.delete(composition);
        }
    }

    /**
     * Keep denormalized stock item names in sync after rename.
     */
    private void syncStockItemNameToBatchesAndReceives(Long stockItemId, String stockItemName) {
        if (stockItemId == null || stockItemName == null) {
            return;
        }

        List<StockBatch> relatedBatches = stockBatchRepository.find("stockItemId", stockItemId).list();
        for (StockBatch batch : relatedBatches) {
            batch.stockItemName = stockItemName;
            stockReceiveRepository.update(
                    "stockItemName = ?1 where stockBatchId = ?2",
                    stockItemName,
                    batch.id
            );
        }
    }
    
    /**
     * Generate stockItemName from:
     * Brand + Formulation + Last Unit of Sell Measure + RouteOfAdmin
     * + TotalComposition + ActiveIngredients (name strength unit, comma-separated)
     */
    private String generateStockItemName(StockItemRequest request) {
        List<String> parts = new java.util.ArrayList<>();
        
        // Brand name
        if (request.brandId != null) {
            Brand brand = brandRepository.findById(request.brandId);
            if (brand != null && brand.name != null) {
                parts.add(brand.name);
            }
        }
        
        // Formulation name
        if (request.formulationId != null) {
            Formulation formulation = formulationRepository.findById(request.formulationId);
            if (formulation != null && formulation.name != null) {
                parts.add(formulation.name);
            }
        }
        
        // Last Unit of Sell Measure
        if (request.lastUnitOfSellMeasure != null && !request.lastUnitOfSellMeasure.trim().isEmpty()) {
            parts.add(request.lastUnitOfSellMeasure.trim());
        }
        
        // Route of Admin title
        if (request.routeOfAdminId != null) {
            RouteOfAdmin routeOfAdmin = routeOfAdminRepository.findById(request.routeOfAdminId);
            if (routeOfAdmin != null && routeOfAdmin.title != null) {
                parts.add(routeOfAdmin.title);
            }
        }
        
        // Total Composition Value + Unit (using abbreviation)
        if (request.totalCompositionValue != null && request.totalCompositionUnitId != null) {
            StrengthUnit unit = strengthUnitRepository.findById(request.totalCompositionUnitId);
            String unitName = "";
            if (unit != null) {
                // Use abbreviation if available, otherwise fall back to title
                if (unit.standardAbbreviation != null && !unit.standardAbbreviation.trim().isEmpty()) {
                    unitName = unit.standardAbbreviation;
                } else if (unit.title != null) {
                    unitName = unit.title;
                }
            }
            parts.add(request.totalCompositionValue + " " + unitName);
        }
        
        // Active Ingredients with their strengths (using abbreviation)
        if (request.ingredients != null && !request.ingredients.isEmpty()) {
            for (StockItemIngredientRequest ingredient : request.ingredients) {
                Long activeIngredientPk = resolveActiveIngredientId(ingredient);
                if (activeIngredientPk != null && ingredient.strength != null && ingredient.unitId != null) {
                    ActiveIngredient activeIngredient = activeIngredientRepository.findById(activeIngredientPk);
                    StrengthUnit unit = strengthUnitRepository.findById(ingredient.unitId);
                    String itemName = (activeIngredient != null && activeIngredient.genericName != null) ? activeIngredient.genericName : "";
                    String unitName = "";
                    if (unit != null) {
                        // Use abbreviation if available, otherwise fall back to title
                        if (unit.standardAbbreviation != null && !unit.standardAbbreviation.trim().isEmpty()) {
                            unitName = unit.standardAbbreviation;
                        } else if (unit.title != null) {
                            unitName = unit.title;
                        }
                    }
                    if (itemName != null && !itemName.trim().isEmpty()) {
                        parts.add(itemName.trim() + " " + ingredient.strength + " " + unitName);
                    }
                }
            }
        }
        
        return String.join(", ", parts);
    }

    private String resolveStockItemName(StockItemRequest request) {
        if (isDrugRequest(request)) {
            return generateDrugDisplayName(request);
        }
        return request.stockItemName == null ? "" : request.stockItemName.trim();
    }

    private String generateDrugDisplayName(StockItemRequest request) {
        List<String> parts = new ArrayList<>();
        if (request.brandId != null) {
            Brand brand = brandRepository.findById(request.brandId);
            if (brand != null && brand.name != null && !brand.name.isBlank()) {
                parts.add(brand.name.trim());
            }
        }
        if (request.ingredients != null) {
            for (StockItemIngredientRequest ingredient : request.ingredients) {
                Long activeIngredientPk = resolveActiveIngredientId(ingredient);
                if (activeIngredientPk == null) {
                    continue;
                }
                ActiveIngredient activeIngredient = activeIngredientRepository.findById(activeIngredientPk);
                String itemName = activeIngredient != null && activeIngredient.genericName != null
                        ? activeIngredient.genericName.trim()
                        : "";
                if (itemName.isEmpty()) {
                    continue;
                }
                StringBuilder line = new StringBuilder(itemName);
                if (ingredient.strength != null) {
                    line.append(' ').append(ingredient.strength.stripTrailingZeros().toPlainString());
                }
                if (ingredient.unitId != null) {
                    StrengthUnit unit = strengthUnitRepository.findById(ingredient.unitId);
                    if (unit != null) {
                        String unitName = unit.standardAbbreviation != null && !unit.standardAbbreviation.isBlank()
                                ? unit.standardAbbreviation.trim()
                                : (unit.title == null ? "" : unit.title.trim());
                        if (!unitName.isEmpty()) {
                            line.append(' ').append(unitName);
                        }
                    }
                }
                parts.add(line.toString());
            }
        }
        return String.join(", ", parts);
    }

    private boolean isDrugRequest(StockItemRequest request) {
        if (request == null || request.itemCategoryId == null) {
            return false;
        }
        Category category = categoryRepository.findById(request.itemCategoryId);
        if (category == null) {
            return false;
        }
        Category top = CategoryLookup.from(categoryRepository.listAll()).resolveTopParent(category);
        String name = top != null && top.name != null ? top.name.trim().toLowerCase() : "";
        return name.equals("drug") || name.equals("drugs");
    }

    private Response duplicateStockItemResponse(StockItemRequest request, Long exceptId) {
        if (isDrugRequest(request)) {
            String signature = compositionSignature(request.ingredients);
            if (signature.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ResponseMessage("A drug needs at least one active ingredient with its strength and unit.", null))
                        .build();
            }
            Long matchId = findStockItemIdByComposition(signature, exceptId);
            if (matchId != null) {
                StockItem other = stockItemRepository.findById(matchId);
                String otherName = other != null && other.stockItemName != null ? other.stockItemName : "";
                String message = otherName.isBlank()
                        ? "A drug with the same active ingredients and composition already exists."
                        : "A drug with the same active ingredients and composition already exists: " + otherName;
                return Response.status(Response.Status.CONFLICT)
                        .entity(new ResponseMessage(message, null))
                        .build();
            }
            return null;
        }

        String name = request.stockItemName == null ? "" : request.stockItemName.trim();
        if (name.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ResponseMessage("Stock item name is required.", null))
                    .build();
        }
        for (StockItem item : stockItemRepository.listAll()) {
            if (item.stockItemName == null) {
                continue;
            }
            if (exceptId != null && exceptId.equals(item.id)) {
                continue;
            }
            if (item.stockItemName.trim().equalsIgnoreCase(name)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(new ResponseMessage("A consumable item with this name already exists.", null))
                        .build();
            }
        }
        return null;
    }

    private Long findStockItemIdByComposition(String signature, Long exceptId) {
        Map<Long, String> keys = compositionKeysByStockItem();
        for (Map.Entry<Long, String> entry : keys.entrySet()) {
            if (exceptId != null && exceptId.equals(entry.getKey())) {
                continue;
            }
            if (signature.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String compositionSignature(List<StockItemIngredientRequest> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (StockItemIngredientRequest ingredient : ingredients) {
            Long activeIngredientPk = resolveActiveIngredientId(ingredient);
            if (activeIngredientPk == null || ingredient.strength == null || ingredient.unitId == null) {
                continue;
            }
            parts.add(activeIngredientPk + "|" + ingredient.strength.stripTrailingZeros().toPlainString() + "|" + ingredient.unitId);
        }
        parts.sort(String::compareTo);
        return String.join("||", parts);
    }

    private Map<Long, String> compositionKeysByStockItem() {
        Map<Long, Strength> strengthById = new HashMap<>();
        for (Strength strength : strengthRepository.listAll()) {
            strengthById.put(strength.id, strength);
        }
        Map<Long, List<String>> partsByStockItem = new HashMap<>();
        for (Composition composition : compositionRepository.listAll()) {
            if (composition.stockItemId == null || composition.strengthId == null) {
                continue;
            }
            Strength strength = strengthById.get(composition.strengthId);
            if (strength == null || strength.activeIngredientId == null || strength.strengthValue == null || strength.strengthUnitId == null) {
                continue;
            }
            partsByStockItem
                    .computeIfAbsent(composition.stockItemId, id -> new ArrayList<>())
                    .add(strength.activeIngredientId + "|" + strength.strengthValue.stripTrailingZeros().toPlainString() + "|" + strength.strengthUnitId);
        }
        Map<Long, String> keys = new HashMap<>();
        for (Map.Entry<Long, List<String>> entry : partsByStockItem.entrySet()) {
            entry.getValue().sort(String::compareTo);
            keys.put(entry.getKey(), String.join("||", entry.getValue()));
        }
        return keys;
    }

    private String compositionKeyFor(Long stockItemId) {
        if (stockItemId == null) {
            return null;
        }
        return compositionKeysByStockItem().get(stockItemId);
    }

    private StockItemDTO toStockItemDTO(StockItem entity) {
        StockItemDTO dto = toStockItemDTO(entity, CategoryLookup.from(categoryRepository.listAll()));
        dto.compositionKey = compositionKeyFor(entity.id);
        return dto;
    }

    private StockItemDTO toStockItemDTO(StockItem entity, CategoryLookup categoryLookup) {
        ensurePrimaryBarcode(entity);
        StockItemDTO dto = new StockItemDTO(entity);
        dto.indications = loadIndicationDtos(entity);
        dto.otherBarcodes = loadOtherBarcodeDtos(entity);
        if (entity.itemCategoryId == null) {
            return dto;
        }

        Category category = categoryLookup.findById(entity.itemCategoryId);
        if (category == null) {
            return dto;
        }

        if (dto.itemCategoryName == null || dto.itemCategoryName.isBlank()) {
            dto.itemCategoryName = category.name;
        }

        Category topParent = categoryLookup.resolveTopParent(category);
        dto.lastCategoryId = topParent.id;
        dto.lastCategoryName = topParent.name;
        return dto;
    }

    /**
     * Replace indication entity rows when {@code request.indications} is provided.
     * Also keeps legacy {@link StockItem#indication} as a joined summary string.
     */
    private void syncIndications(StockItem stockItem, StockItemRequest request) {
        if (stockItem == null || stockItem.id == null || request == null) {
            return;
        }
        if (request.indications == null) {
            return;
        }

        deleteIndicationsByStockItemId(stockItem.id);

        List<String> texts = new ArrayList<>();
        int order = 0;
        for (StockItemIndicationRequest row : request.indications) {
            String text = row != null && row.text != null ? row.text.trim() : null;
            if (text == null || text.isEmpty()) {
                continue;
            }
            StockItemIndication indication = new StockItemIndication();
            indication.stockItemId = stockItem.id;
            indication.text = text;
            indication.sortOrder = order++;
            stockItemIndicationRepository.persist(indication);
            texts.add(text);
        }
        stockItem.indication = texts.isEmpty() ? null : String.join("; ", texts);
    }

    private void deleteIndicationsByStockItemId(Long stockItemId) {
        List<StockItemIndication> rows = stockItemIndicationRepository.find("stockItemId", stockItemId).list();
        for (StockItemIndication row : rows) {
            stockItemIndicationRepository.delete(row);
        }
    }

    private List<StockItemIndicationDTO> loadIndicationDtos(StockItem entity) {
        if (entity == null || entity.id == null) {
            return List.of();
        }
        List<StockItemIndicationDTO> rows = stockItemIndicationRepository
                .list("stockItemId", Sort.ascending("sortOrder", "id"), entity.id)
                .stream()
                .map(StockItemIndicationDTO::new)
                .collect(Collectors.toList());
        if (!rows.isEmpty()) {
            return rows;
        }
        // Legacy fallback: split single TEXT field into editable rows
        if (entity.indication == null || entity.indication.isBlank()) {
            return List.of();
        }
        List<StockItemIndicationDTO> fallback = new ArrayList<>();
        int order = 0;
        for (String part : entity.indication.split("[;\\n]+")) {
            String text = part != null ? part.trim() : "";
            if (text.isEmpty()) {
                continue;
            }
            fallback.add(new StockItemIndicationDTO(null, text, order++));
        }
        return fallback;
    }

    /**
     * Keep an existing system barcode. When blank, store the requested code if it is free,
     * otherwise assign VH plus the item id.
     */
    private void applyPrimaryBarcode(StockItem stockItem, String requested) {
        if (stockItem == null || stockItem.id == null) {
            return;
        }
        if (stockItem.barcode != null && !stockItem.barcode.isBlank()) {
            return;
        }
        String candidate = normalizeBarcode(requested);
        if (candidate != null && !isBarcodeTaken(candidate, stockItem.id)) {
            stockItem.barcode = candidate;
            return;
        }
        String generated = "VH" + String.format("%08d", stockItem.id);
        int suffix = 0;
        while (isBarcodeTaken(generated, stockItem.id)) {
            suffix++;
            generated = "VH" + String.format("%08d", stockItem.id) + suffix;
        }
        stockItem.barcode = generated;
    }

    private void ensurePrimaryBarcode(StockItem stockItem) {
        applyPrimaryBarcode(stockItem, null);
    }

    private void syncOtherBarcodes(StockItem stockItem, StockItemRequest request) {
        if (stockItem == null || stockItem.id == null || request == null || request.otherBarcodes == null) {
            return;
        }
        deleteOtherBarcodes(stockItem.id);
        stockItemBarcodeRepository.flush();

        String primary = normalizeBarcode(stockItem.barcode);
        Set<String> seen = new HashSet<>();
        int order = 0;
        for (StockItemBarcodeRequest row : request.otherBarcodes) {
            String code = normalizeBarcode(row != null ? row.code : null);
            if (code == null || !seen.add(code)) {
                continue;
            }
            if (code.equals(primary) || isBarcodeTaken(code, stockItem.id)) {
                throw new IllegalArgumentException("Barcode " + code + " is already used.");
            }
            StockItemBarcode barcode = new StockItemBarcode();
            barcode.stockItemId = stockItem.id;
            barcode.code = code;
            barcode.sortOrder = order++;
            stockItemBarcodeRepository.persist(barcode);
        }
    }

    private void deleteOtherBarcodes(Long stockItemId) {
        if (stockItemId == null) {
            return;
        }
        List<StockItemBarcode> rows = stockItemBarcodeRepository.find("stockItemId", stockItemId).list();
        for (StockItemBarcode row : rows) {
            stockItemBarcodeRepository.delete(row);
        }
    }

    private List<StockItemBarcodeDTO> loadOtherBarcodeDtos(StockItem entity) {
        if (entity == null || entity.id == null) {
            return List.of();
        }
        return stockItemBarcodeRepository
                .list("stockItemId", Sort.ascending("sortOrder", "id"), entity.id)
                .stream()
                .map(StockItemBarcodeDTO::new)
                .collect(Collectors.toList());
    }

    private boolean isBarcodeTaken(String code, Long exceptStockItemId) {
        String normalized = normalizeBarcode(code);
        if (normalized == null) {
            return false;
        }
        StockItem owner = stockItemRepository.find("barcode", normalized).firstResult();
        if (owner != null && (exceptStockItemId == null || !exceptStockItemId.equals(owner.id))) {
            return true;
        }
        StockItemBarcode other = stockItemBarcodeRepository.find("code", normalized).firstResult();
        return other != null && (exceptStockItemId == null || !exceptStockItemId.equals(other.stockItemId));
    }

    private String normalizeBarcode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim().toUpperCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class CategoryLookup {
        private final Map<Long, Category> byId;
        private final Map<Long, Long> parentIdById;

        private CategoryLookup(Map<Long, Category> byId, Map<Long, Long> parentIdById) {
            this.byId = byId;
            this.parentIdById = parentIdById;
        }

        static CategoryLookup from(List<Category> categories) {
            Map<Long, Category> byId = new HashMap<>();
            Map<Long, Long> parentIdById = new HashMap<>();
            for (Category category : categories) {
                byId.put(category.id, category);
                if (category.parent != null) {
                    parentIdById.put(category.id, category.parent.id);
                }
            }
            return new CategoryLookup(byId, parentIdById);
        }

        Category findById(Long id) {
            return byId.get(id);
        }

        Category resolveTopParent(Category category) {
            Category current = category;
            Set<Long> visited = new HashSet<>();
            while (parentIdById.containsKey(current.id)) {
                if (!visited.add(current.id)) {
                    break;
                }
                Long parentId = parentIdById.get(current.id);
                Category parent = byId.get(parentId);
                if (parent == null) {
                    break;
                }
                current = parent;
            }
            return current;
        }
    }

    private void applyPrescribingMeasureFields(StockItem stockItem, StockItemRequest request) {
        stockItem.lastUnitOfPrescribingMeasureStrengthUnit = request.lastUnitOfPrescribingMeasureStrengthUnit;
        if (request.lastUnitOfPrescribingMeasureStrengthUnit != null) {
            StrengthUnit unit = strengthUnitRepository.findById(request.lastUnitOfPrescribingMeasureStrengthUnit);
            if (unit != null) {
                stockItem.lastUnitOfPrescribingMeasureStrengthUnitTitle = unit.prescribingLabel();
            }
        }
        stockItem.prescribingPeriodBasis = request.prescribingPeriodBasis;
        stockItem.prescribingDosageJson = request.prescribingDosageJson;
        stockItem.prescribingFrequencyMode = request.prescribingFrequencyMode;
        stockItem.prescribingFrequencyFixed = request.prescribingFrequencyFixed;
        stockItem.prescribingFrequencyCustomHours = request.prescribingFrequencyCustomHours;
    }

    private static Long resolveActiveIngredientId(StockItemIngredientRequest ingredient) {
        if (ingredient == null) {
            return null;
        }
        if (ingredient.activeIngredientId != null) {
            return ingredient.activeIngredientId;
        }
        return ingredient.itemId;
    }
}
