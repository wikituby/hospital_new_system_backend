package org.example.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** One row means this change was already exchanged with that peer. */
@Entity
@Table(
        name = "sync_delivery",
        uniqueConstraints = @UniqueConstraint(columnNames = {"eventUid", "peerKey"})
)
public class SyncDelivery extends PanacheEntity {

    @Column(nullable = false, length = 64)
    public String eventUid;

    @Column(nullable = false, length = 300)
    public String peerKey;
}
