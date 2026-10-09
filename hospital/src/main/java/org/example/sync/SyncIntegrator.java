package org.example.sync;

import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;

public class SyncIntegrator implements Integrator {

    @Override
    public void integrate(Metadata metadata, SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {
        EventListenerRegistry registry = serviceRegistry.getService(EventListenerRegistry.class);
        SyncChangeListener listener = new SyncChangeListener();
        registry.appendListeners(EventType.POST_COMMIT_INSERT, listener);
        registry.appendListeners(EventType.POST_COMMIT_UPDATE, listener);
        registry.appendListeners(EventType.POST_COMMIT_DELETE, listener);
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {
    }
}
