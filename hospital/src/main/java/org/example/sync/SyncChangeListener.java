package org.example.sync;

import io.quarkus.arc.Arc;
import org.hibernate.event.spi.PostCommitDeleteEventListener;
import org.hibernate.event.spi.PostCommitInsertEventListener;
import org.hibernate.event.spi.PostCommitUpdateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.jboss.logging.Logger;

/**
 * Registered on the post-commit events, so a rolled-back hospital change
 * is never written into the sync log.
 */
public class SyncChangeListener implements PostCommitInsertEventListener, PostCommitUpdateEventListener, PostCommitDeleteEventListener {

    private static final Logger LOG = Logger.getLogger(SyncChangeListener.class);

    @Override
    public void onPostInsert(PostInsertEvent event) {
        record(event.getEntity(), "INSERT");
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        record(event.getEntity(), "UPDATE");
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        record(event.getEntity(), "DELETE");
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return true;
    }

    @Override
    public void onPostInsertCommitFailed(PostInsertEvent event) {
    }

    @Override
    public void onPostUpdateCommitFailed(PostUpdateEvent event) {
    }

    @Override
    public void onPostDeleteCommitFailed(PostDeleteEvent event) {
    }

    private void record(Object entity, String operation) {
        try {
            SyncService service = Arc.container().instance(SyncService.class).get();
            if (service != null) {
                service.recordLocal(entity, operation);
            }
        } catch (Exception ex) {
            LOG.warnf("Sync listener skipped a %s: %s", operation, ex.getMessage());
        }
    }
}
