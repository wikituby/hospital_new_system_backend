package org.example.procedure.itemUsedInProcedure.domains;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

@Entity
public class ItemUsed extends PanacheEntity {

    @Column(name = "procedure_id")
    public Long procedureId;

    /** Unit sell model these items belong to. Null only for older procedure-level rows. */
    @Column(name = "unit_selling_model_id")
    public Long unitSellingModelId;

    @Column(name = "item_id")
    public Long itemId;

    /** ITEM or FEE. Fees are costs such as labour or maintenance, not stock items. */
    @Column(name = "line_type", length = 20)
    public String lineType;

    @Column
    public BigDecimal quantityUsed;

    /** Cost of one unit of this item when it is used in the procedure. */
    @Column(name = "unit_cost_price")
    public BigDecimal unitCostPrice;

    /** This model does not use the service's default row for the same item. */
    @Column
    public Boolean excluded;

    @Column
    public String procedureName;

    @Column
    public  String itemName;


}






