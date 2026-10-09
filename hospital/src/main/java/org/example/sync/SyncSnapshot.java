package org.example.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** Older sync events folded together so the live log does not grow forever. */
@Entity
@Table(name = "sync_snapshot")
public class SyncSnapshot extends PanacheEntity {

    /** Highest sync_event id included in this snapshot. */
    public long throughSequence;

    @Lob
    @Column(nullable = false)
    public String payload;

    public static SyncSnapshot latest() {
        return find("order by throughSequence desc").firstResult();
    }
}
