package org.example.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "sync_event")
public class SyncEvent extends PanacheEntity {

    @Column(unique = true, nullable = false, length = 64)
    public String eventUid;

    @Column(length = 80)
    public String deviceId;

    @Column(nullable = false, length = 300)
    public String entityType;

    @Column(length = 120)
    public String entityKey;

    @Column(nullable = false, length = 16)
    public String operation;

    @Lob
    @Column(nullable = false)
    public String payload;

    public LocalDateTime occurredAt;

    /** Local changes still waiting to be sent to a peer. */
    public boolean pushed;

    public static SyncEvent findByUid(String eventUid) {
        return find("eventUid", eventUid).firstResult();
    }
}
