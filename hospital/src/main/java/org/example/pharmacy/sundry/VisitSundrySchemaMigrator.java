package org.example.pharmacy.sundry;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Sundries follow the pharmacy source setting: a stock batch or a shop item.
 * Hibernate update does not relax an existing NOT NULL column, so
 * item-only rows fail with "stock_batch_id cannot be null".
 */
@ApplicationScoped
public class VisitSundrySchemaMigrator {

    private static final Logger LOG = Logger.getLogger(VisitSundrySchemaMigrator.class);

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "mysql")
    String dbKind;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        boolean postgres = dbKind != null && dbKind.toLowerCase().contains("postgres");
        try {
            if (postgres) {
                entityManager.createNativeQuery(
                        "ALTER TABLE VisitSundry ALTER COLUMN stock_batch_id DROP NOT NULL"
                ).executeUpdate();
            } else {
                entityManager.createNativeQuery(
                        "ALTER TABLE VisitSundry MODIFY COLUMN stock_batch_id BIGINT NULL"
                ).executeUpdate();
            }
            LOG.info("VisitSundry.stock_batch_id is nullable");
        } catch (Exception e) {
            LOG.warnf(e, "VisitSundry stock_batch_id migrate skipped: %s", e.getMessage());
        }
    }
}