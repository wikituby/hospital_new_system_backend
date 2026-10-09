package org.example.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Remembers the last sequence applied from a particular peer. */
@Entity
@Table(name = "sync_cursor")
public class SyncCursor extends PanacheEntity {

    @Column(unique = true, nullable = false, length = 300)
    public String peerKey;

    public long lastAppliedSequence;

    public static SyncCursor findByPeer(String peerKey) {
        return find("peerKey", peerKey).firstResult();
    }
}
