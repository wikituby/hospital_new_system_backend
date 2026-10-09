package org.example.configuration.security;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.TokenAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.ws.rs.ext.Provider;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

@Alternative
@Priority(1)
@ApplicationScoped
@Provider
public class CustomAwareJWTAuthMechanism implements HttpAuthenticationMechanism {
    // CORS is handled only by quarkus.http.cors=* in application.properties.
    // Do not implement ContainerResponseFilter here — duplicate Access-Control-*
    // headers make browsers reject /health even when status is 200.

    protected static final String AUTHORIZATION_HEADER = "Authorization";
    protected static final String BEARER = "Bearer";

    @Inject
    JwtUtils jwtUtils;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        HttpServerRequest request = context.request();
        String jwt = parseJwt(request);

        if ("OPTIONS".equals(request.method().name())) {
            return Uni.createFrom().optional(Optional.empty());
        }

        // Angular UI hosted on the clinic server (same-origin with /health_care APIs).
        if (isPublicClinicUiAsset(request.path())) {
            return Uni.createFrom().optional(Optional.empty());
        }

        if (request.path().startsWith("/auth/user-login") ||
                request.path().startsWith("/health_care/auth/user-login") ||
                request.path().contains("/shop-item/get-all-Items") ||
                request.path().contains("/user-management/signup") ||
                request.path().contains("/auth/update-password") ||
                request.path().contains("/health_care/user-management/update-password/") ||
                request.path().contains("/health_care/get-all-courses") ||
                request.path().contains("/health_care/create-new-course") ||
                request.path().contains("/health_care/course/update-course") ||
                request.path().contains("/health_care/shop-cart/add-to-cart") ||
                request.path().contains("/health_care/Patient-management/create-new-service-category") ||
                request.path().contains("/health_care/Patient-management/update-service-category") ||
                request.path().contains("/health_care/Patient-management/get-all-service-categories") ||
                request.path().contains("/health_care/Patient-management/fix-procedure-requested-names") ||


                request.path().contains("/health_care/Patient-management/create-new-service-type") ||
                request.path().contains("/health_care/Patient-management/update-service-type") ||
                request.path().contains("/health_care/Patient-management/get-all-service-types") ||
                request.path().contains("/health_care/Patient-management/get-all-complaint-types") ||
                request.path().contains("/health_care/Patient-management/get-complaint-type/") ||
                request.path().contains("/health_care/Patient-management/create-new-complaint-type") ||
                request.path().contains("/health_care/Patient-management/update-complaint-type/") ||
                request.path().contains("/health_care/Patient-management/delete-complaint-type/") ||
                request.path().contains("/health_care/Patient-management/get-all-sites") ||
                request.path().contains("/health_care/Patient-management/get-complaint-site/") ||
                request.path().contains("/health_care/Patient-management/create-new-complaint-site") ||
                request.path().contains("/health_care/Patient-management/create-new-site") ||
                request.path().contains("/health_care/Patient-management/update-complaint-site/") ||
                request.path().contains("/health_care/Patient-management/delete-complaint-site/") ||
                request.path().contains("/health_care/Patient-management/get-all-complaint-options") ||
                request.path().contains("/health_care/Patient-management/get-complaint-option/") ||
                request.path().contains("/health_care/Patient-management/create-new-complaint-option") ||
                request.path().contains("/health_care/Patient-management/update-complaint-option/") ||
                request.path().contains("/health_care/Patient-management/delete-complaint-option/") ||
                request.path().contains("/health_care/Patient-management/get-all-diagnosis-types") ||
                request.path().contains("/health_care/Patient-management/get-diagnosis-type/") ||
                request.path().contains("/health_care/Patient-management/create-new-diagnosis-type") ||
                request.path().contains("/health_care/Patient-management/update-diagnosis-type/") ||
                request.path().contains("/health_care/Patient-management/delete-diagnosis-type/") ||
                request.path().contains("/health_care/Patient-management/get-diagnoses-by-visit/") ||
                request.path().contains("/health_care/Patient-management/get-diagnosis/") ||
                request.path().contains("/health_care/Patient-management/create-diagnosis/") ||
                request.path().contains("/health_care/Patient-management/update-diagnosis/") ||
                request.path().contains("/health_care/Patient-management/delete-diagnosis/") ||
                request.path().contains("/health_care/Patient-management/get-presenting-complaints-by-visit/") ||
                request.path().contains("/health_care/Patient-management/create-presenting-complaint/") ||
                request.path().contains("/health_care/Patient-management/update-presenting-complaint/") ||
                request.path().contains("/health_care/Patient-management/delete-presenting-complaint/") ||
                request.path().contains("/health_care/Patient-management/get-physical-examinations-by-visit/") ||
                request.path().contains("/health_care/Patient-management/create-physical-examination/") ||
                request.path().contains("/health_care/Patient-management/update-physical-examination/") ||
                request.path().contains("/health_care/Patient-management/delete-physical-examination/") ||
                request.path().contains("/health_care/health") ||
                request.path().endsWith("/health") ||
                request.path().contains("/sync/pull") ||
                request.path().contains("/sync/push") ||
                request.path().contains("/business-settings/public") ||
                request.path().contains("/health_care/business-settings/public") ||
                request.path().contains("/support/website-contact/public") ||
                request.path().contains("/health_care/support/website-contact/public") ||
                request.path().contains("/swagger") ||
                request.path().contains("/openapi") ||
                request.path().contains("/q/swagger-ui") ||
                request.path().contains("/q/openapi") ||
                request.path().contains("/messages/ws") ||
                request.path().contains("/health_care/messages/ws") ||
                request.path().contains("/health_care/diagnostics-management/get-all-generalUs") ||
                request.path().contains("/health_care/diagnostics-management/update-general-lab-report/") ||
                request.path().contains("/health_care/diagnostics-management/get-general-lab-report-by-request-id/") ||
                request.path().contains("/health_care/diagnostics-management/update-urinalysis-report/") ||
                request.path().contains("/health_care/diagnostics-management/get-urinalysis-report-by-request-id/") ||
                request.path().contains("/health_care/diagnostics-management/update-cbc-report/") ||
                request.path().contains("/health_care/diagnostics-management/get-cbc-report-by-request-id/") ||
                request.path().contains("/health_care/diagnostics-management/update-parasitology-stool-report/") ||
                request.path().contains("/health_care/diagnostics-management/get-parasitology-stool-report-by-request-id/") ||

                request.path().contains("/health_care/diagnostics-management/scan/generate-pdf/") ||
                request.path().contains("/health_care/diagnostics-management/get-malaria-report-by-request-id/") ||
                request.path().contains("/health_care/diagnostics-management/update-malaria-report/") ||
                request.path().contains("/health_care/shop-item/update-shelfNumbers") ||
                request.path().contains("/health_care/diagnostics-management/scan-generate-pdf/") ||
                request.path().contains("/health_care/Patient-management/add-new-procedure-categories") ||

                request.path().contains("/health_care/Patient-management/update-procedure-category/") ||

                request.path().contains("/health_care/Patient-management/get-all-procedure-categories") ||

                request.path().contains("/health_care/Patient-management/get-procedure-category/") ||

                request.path().contains("/health_care/Patient-management/delete-procedure-category/") ||

                request.path().contains("/health_care/Patient-management/update-missing-procedure-references") ||

                request.path().contains("/health_care/item-categories") ||

                request.path().contains("/health_care/Patient-management/get-all-consultations") ||

                request.path().contains("/health_care/Patient-management/get-all-ultrasound-scan-procedures") ||
                request.path().contains("/health_care/diagnostics-management/get-scan-report-by-request-id/") ||
                request.path().contains("/health_care/Patient-management/compassion/invoice/generate-pdf") ||
                request.path().contains("/health_care/Patient-management/get-all-lab-procedures") ||
                request.path().contains("/health_care/Patient-management/dashboard-summary") ||
                request.path().contains("/health_care/Patient-management/get-all-dental-procedures") ||
                request.path().contains("/health_care/Patient-management/get-all-maternity-procedures") ||

                request.path().contains("/health_care/Patient-management/compassion-invoice/generate-pdf/") ||
                request.path().contains("group/invoice-period/generate-pdf") ||
                request.path().contains("group/invoice-period/generate-docx") ||


                request.path().contains("/health_care/shop-item/add-new-Items") ||
                request.path().contains("/health_care/shop-cart/get-cart-items/") ||
                request.path().contains("/health_care/Patient-management/get-all-patients") ||
                request.path().contains("/health_care/Patient-management/get-patient") ||
                request.path().contains("/health_care/Patient-management/update-patient/") ||
                request.path().contains("/health_care/Patient-management/create-new-patient-visit") ||
                request.path().contains("/health_care/Patient-management/create-new-InitialTriageVitals") ||
                request.path().contains("/health_care/Patient-management/get-all-InitialTriageVitals") ||
                request.path().contains("/health_care/Patient-management/create-new-Consultation") ||
                request.path().contains("/health_care/Patient-management/create-new-labTest") ||
                request.path().contains("/health_care/diagnostics-management/create-new-general-scan-report") ||
                request.path().contains("/health_care/diagnostics-management/update-scan-report/") ||
                request.path().contains("/health_care/Patient-management/initialize-visit-groups") ||
                request.path().contains("/health_care/Patient-management/get-visit-advanced-search") ||
                request.path().contains("/health_care/Patient-management/filter-visits") ||

                request.path().contains("/health_care/items-used/add-to-item-used") ||
                request.path().contains("/health_care/Patient-management/get-All-used-items") ||
                request.path().contains("/health_care/Patient-management/get-used-items/") ||
                request.path().contains("/health_care/Patient-management/add-to-item-used") ||
                request.path().contains("/health_care/Patient-management/get-InitialTriageVitals-visit") ||
                request.path().contains("/health_care/Patient-management/get-all-labTest") ||
                request.path().contains("/health_care/Patient-management/create-new-InPatientTreatmentGiven") ||
                request.path().contains("/health_care/Patient-management/create-new-procedure") ||
                request.path().contains("/health_care/Patient-management/get-all-procedures") ||
                request.path().contains("/health_care/Patient-management/procedures/") ||
                request.path().contains("/health_care/Patient-management/create-new-treatmentRequest") ||
                request.path().contains("/health_care/Patient-management/create-new-Recommendation") ||
                request.path().contains("/health_care/Patient-management/create-new-VitalsMonitoring") ||
                request.path().contains("/health_care/Patient-management/get-Initial-TriageVitals-visit-by-id") ||
                request.path().contains("/health_care/Patient-management/get-patient-Visit-List-by-id") ||
                request.path().contains("/health_care/Patient-management/get-patient-visit-by-visit-id") ||
                request.path().contains("/health_care/Patient-management/get-procedure-Requested-with-type-LabTest-by-visit-id") ||
                request.path().contains("/health_care/Patient-management/create-new-payment") ||
                request.path().contains("/health_care/Patient-management/pay-all-patient-debt") ||
                request.path().contains("/health_care/Patient-management/get-ultrasound-scan-by-visit-id") ||
                request.path().contains("/health_care/Patient-management/get-total-cost-of-all-procedures-by-visit-id/") ||
                request.path().contains("/health_care/Patient-management/get-total-cost-of-all-procedures-v2-by-visit-id") ||
                request.path().contains("/health_care/Patient-management/get-other-procedures-by-visit-id") ||
                request.path().contains("/health_care/Patient-management/create-new-Invoice") ||
                request.path().contains("/health_care/Patient-management/get-total-cost-of-every-service-by-visit-id") ||
                request.path().contains("/health_care/Patient-management/sync-visit-billing/") ||
                request.path().contains("/health_care/Patient-management/get-all-invoices") ||
                request.path().contains("/health_care/shop-item/") ||
                request.path().contains("/health_care/shop-item/delete-item") ||
                request.path().contains("/health_care/shop-item/delete-stock-received/") ||
                request.path().contains("/health_care/shop-item/add-new-bulk-items") ||
                request.path().contains("/health_care/shop-item/update-item") ||
                request.path().contains("/health_care/shop-item/update-bulk-items-after-service-order") ||
                request.path().contains("/health_care/hospital-management/delete") ||
                request.path().contains("/health_care/Patient-management/create-new-store") ||
                request.path().contains("/health_care/Patient-management/get-all-stores") ||
                request.path().contains("/health_care/stores") ||
                request.path().contains("/health_care/Patient-management/update-treatmentRequest/") ||
                request.path().contains("/health_care/Patient-management/delete-requested-treatment-by-id/") ||
                request.path().contains("/health_care/Patient-management/create-treatment-chart/") ||
                request.path().contains("/health_care/Patient-management/update-treatment-chart/") ||
                request.path().contains("/health_care/Patient-management/get-treatment-chart-by-request-id/") ||
                request.path().contains("/health_care/Patient-management/get-treatment-charts-by-visit-id/") ||
                request.path().contains("/health_care/Patient-management/delete-treatment-chart/") ||
                request.path().contains("/health_care/Patient-management/create-visit-sundry/") ||
                request.path().contains("/health_care/Patient-management/get-visit-sundries-by-visit-id/") ||
                request.path().contains("/health_care/Patient-management/delete-visit-sundry/") ||



                request.path().contains("/health_care/Patient-management/get-all-procedures-requested-by-visit-id") ||

                request.path().contains("/health_care/Patient-management/delete-item-used-id") ||

                request.path().contains("/health_care/Patient-management/update-patient-visit-status/") ||

                request.path().contains("/course/Patient-management/get-all-procedures-requested-by-visit-id") ||

                request.path().contains("/health_care/Patient-management/get-payment-advanced-search") ||

                request.path().contains("/health_care/financial-management/get-all-expense-transactions") ||

                request.path().contains("/health_care/financial-management/get-all-expense-accounts") ||

                request.path().contains("/health_care/financial-management/get-all-expense-categories") ||


                request.path().contains("/health_care/financial-management/create-new-expense-account") ||


                request.path().contains("/health_care/financial-management/delete-expense-transaction") ||







                request.path().contains("/health_care/Patient-management/get-payment-List-by-visit-id/") ||

                request.path().contains("/health_care/Patient-management/get-invoice-by-visit-id/") ||
                request.path().contains("/health_care/Patient-management/get-consultations-visit-by-id/") ||
                request.path().contains("/health_care/Patient-management/get-consultation-documents-by-visit/") ||
                request.path().contains("/health_care/Patient-management/create-consultation-document/") ||
                request.path().contains("/health_care/Patient-management/delete-consultation-document/") ||
                request.path().contains("/health_care/financial-management/create-new-expense-account/") ||
                request.path().contains("/health_care/financial-management/create-new-expense-category/") ||

                request.path().contains("/health_care/financial-management/create-new-expense-transaction/") ||

                request.path().contains("/health_care/financial-management/get-all-loans") ||
                request.path().contains("/health_care/financial-management/create-new-loan") ||
                request.path().contains("/health_care/financial-management/update-loan/") ||
                request.path().contains("/health_care/financial-management/record-loan-repayment/") ||
                request.path().contains("/health_care/financial-management/delete-loan/") ||

                request.path().contains("/health_care/financial-management/get-income-summary") ||
                request.path().contains("/health_care/financial-management/get-all-income-entries") ||
                request.path().contains("/health_care/financial-management/create-new-income-entry") ||
                request.path().contains("/health_care/financial-management/update-income-entry/") ||
                request.path().contains("/health_care/financial-management/delete-income-entry/") ||
                request.path().contains("/health_care/financial-management/get-all-income-categories") ||
                request.path().contains("/health_care/financial-management/create-new-income-category") ||
                request.path().contains("/health_care/financial-management/update-income-category/") ||
                request.path().contains("/health_care/financial-management/delete-income-category/") ||
                request.path().contains("/health_care/financial-management/get-all-income-accounts") ||
                request.path().contains("/health_care/financial-management/create-new-income-account") ||
                request.path().contains("/health_care/financial-management/update-income-account/") ||
                request.path().contains("/health_care/financial-management/delete-income-account/") ||
                request.path().contains("/health_care/financial-management/get-all-income-payment-methods") ||
                request.path().contains("/health_care/financial-management/create-new-income-payment-method") ||
                request.path().contains("/health_care/financial-management/update-income-payment-method/") ||
                request.path().contains("/health_care/financial-management/delete-income-payment-method/") ||
                request.path().contains("/health_care/financial-management/get-all-income-sources") ||
                request.path().contains("/health_care/financial-management/create-new-income-source") ||
                request.path().contains("/health_care/financial-management/update-income-source/") ||
                request.path().contains("/health_care/financial-management/delete-income-source/") ||




                request.path().contains("/health_care/Patient-management/get-all-hospital-modules") ||
                request.path().contains("/health_care/Patient-management/create-hospital-module") ||
                request.path().contains("/health_care/Patient-management/update-hospital-module/") ||
                request.path().contains("/health_care/Patient-management/delete-hospital-module/") ||
                request.path().contains("/health_care/Patient-management/get-hospital-clinics") ||
                request.path().contains("/health_care/Patient-management/create-hospital-clinic") ||
                request.path().contains("/health_care/Patient-management/update-hospital-clinic/") ||
                request.path().contains("/health_care/Patient-management/delete-hospital-clinic/") ||
                request.path().contains("/health_care/Patient-management/queue-patient") ||
                request.path().contains("/health_care/Patient-management/discharge-patient-from-queue") ||
                request.path().contains("/health_care/Patient-management/discharge-patient-queue-entry/") ||
                request.path().contains("/health_care/Patient-management/get-patient-queue-entries") ||
                request.path().contains("/health_care/Patient-management/get-latest-patient-queue-entries") ||
                request.path().contains("/health_care/Patient-management/get-hospital-directory") ||
                request.path().contains("/health_care/Patient-management/update-patient-queue-entry/") ||
                request.path().contains("/health_care/Patient-management/call-patient-queue-entry/") ||
                request.path().contains("/health_care/Patient-management/serve-patient-queue-entry/") ||
                request.path().contains("/health_care/Patient-management/complete-patient-queue-entry/") ||
                request.path().contains("/health_care/Patient-management/cancel-patient-queue-entry/") ||

                request.path().contains("/health_care/Patient-management/get-all-patient-groups") ||
                request.path().contains("/health_care/Patient-management/update-patient-group/") ||
                request.path().contains("/health_care/Patient-management/create-new-patient-group") ||
                request.path().contains("/health_care/Patient-management/update-vital/") ||
                request.path().contains("/health_care/Patient-management/delete-vital-by-id/") ||
                request.path().contains("/health_care/Patient-management/create-multiple-patients") ||
                request.path().contains("/health_care/Patient-management/delete-service") ||
                request.path().contains("/health_care/Patient-management/update-stock") ||
                request.path().contains("/health_care/Patient-management/get-latest-patient-visit-by-patient-id") ||


                request.path().contains("/health_care/Patient-management/update-service") ||

                request.path().contains("/health_care/Patient-management/update-patient-visit/") ||

                //request.path().contains("/health_care/Patient-management/create-new-patient") ||
                request.path().contains("/health_care/Patient-management/get-patient-group/") ||
                request.path().contains("/health_care/Patient-management/get-patient-group-by-id/") ||
                request.path().contains("/health_care/Patient-management/update-invoice") ||

                request.path().contains("/health_care/Patient-management/statement/generate-pdf") ||

                request.path().contains("/health_care/Patient-management/labResultsPdf/generate-pdf") ||
                

                request.path().contains("/health_care/Patient-management/visit-lab-reports/generate-pdf") ||

                request.path().contains("/health_care/Patient-management/invoice/generate-pdf") ||
                request.path().contains("/health_care/Patient-management/get-treatment-requested-by-visit-id") ||
                request.path().contains("/health_care/shop-item/receive-new-stock") ||
                request.path().contains("/health_care/shop-item/receive-stock") ||
                request.path().contains("/get-all-labTest-procedures") ||
                request.path().contains("/health_care/Patient-management/delete-requested-procedure-by-id/") ||
                request.path().contains("create-new-procedure-requested/{id}") ||
                request.path().contains("/health_care/Patient-management/get-total-cost-of-every-service-by-patient-id/") ||
                request.path().contains("/health_care/Patient-management/delete-payment-id/") ||
                request.path().contains("/health_care/Patient-management/update-payment-id/") ||
                request.path().contains("/health_care/Patient-management/delete-invoice-id/") ||
                request.path().contains("/health_care/Patient-management/get-procedure-requested-by-id/") ||
                request.path().contains("/health_care/Patient-management/create-bulk-procedures") ||
                request.path().contains("/health_care/Patient-management/items-used/") ||
                request.path().contains("/health_care/Patient-management/add-itemUsed") ||
                request.path().contains("get-all-scan-procedures") ||
                request.path().contains("get-Other-Procedures") ||
                request.path().contains("/health_care/Patient-management/get-total-cost-of-all-lab-tests") ||
                request.path().contains("/health_care/Patient-management/create-new-patient-with-group-id") ||
                request.path().contains("/health_care/Patient-management/get-patient-with-max-number") ||
                request.path().contains("/health_care/Patient-management/delete-patient-by-id/") ||
                request.path().contains("/auth/reset-link") ||
                request.path().contains("/health_care/user-management/get-user") ||
                request.path().contains("/health_care/user-management/update-user") ||
                request.path().contains("/health_care/user-management/update-profile-pic/") ||
                request.path().contains("/health_care/user-management/update-password/") ||
                request.path().startsWith("/user-management/update-password/") ||
                request.path().startsWith("/health_care/user-management/update-password/") ||
                
                request.path().contains("/health_care/user-management/get-all-users") ||
                request.path().contains("/health_care/user-management/create-user") ||
                request.path().contains("/health_care/user-management/get-all-roles") ||
                request.path().contains("/health_care/subscription/status") ||
                request.path().contains("/health_care/subscription/activate") ||
                request.path().contains("/health_care/subscription/cancel") ||
                request.path().contains("/health_care/subscription/mobile-money") ||
                request.path().contains("/health_care/subscription/dgateway") ||
                request.path().contains("/health_care/webhooks/dgateway") ||
                request.path().contains("/health_care/subscription/flutterwave") ||
                request.path().contains("/health_care/webhooks/flutterwave") ||
                request.path().contains("/health_care/platform/activation-tokens") ||
                request.path().contains("/health_care/shop-item/get-Items-advanced-search") ||
                request.path().contains("/health_care/shop-item/add-new-Items") ||
                request.path().contains("/health_care/shop-item/search") ||
                request.path().contains("/shop-item/generate-pdf") ||
                request.path().contains("/health_care/stock-item/get-all-stock-items") ||
                request.path().contains("/health_care/stock-item/") ||
                request.path().contains("/health_care/stock-records/") ||
                request.path().contains("/health_care/pharmacy-otc/") ||
                request.path().contains("/health_care/stock-tracking/") ||
                isHospitalItemPathWithoutTransferAuth(request.path()) ||
                request.path().contains("/health_care/expiry-item-register/") ||
                request.path().contains("/health_care/adjustment-type/") ||
                request.path().contains("/health_care/stock-adjustment/") ||

                request.path().contains("/health_care/stock-verification/") ||
                request.path().contains("/health_care/stock-initializer/") ||
                request.path().contains("/health_care/assets/") ||
                request.path().contains("/health_care/Patient-management/create-new-store") ||
                request.path().contains("/health_care/Patient-management/get-all-stores") ||
                request.path().contains("/health_care/Patient-management/update-store/") ||
                request.path().contains("/health_care/Patient-management/delete-store/") ||
                request.path().contains("/health_care/Patient-management/get-store/") ||
                request.path().contains("/health_care/stores/") ||
                request.path().contains("/user-login")) {
            return Uni.createFrom().optional(Optional.empty());
        } else {
            if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
                String login = jwtUtils.getLoginFromJwtToken(jwt);
                Set<String> userRole = jwtUtils.getRoles();
                QuarkusSecurityIdentity identity = QuarkusSecurityIdentity.builder()
                        .setPrincipal(new QuarkusPrincipal(login))
                        .addRoles(userRole)
                        .build();
                return Uni.createFrom().item(identity);
            }
            return Uni.createFrom().failure(new AuthenticationFailedException());
        }
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        ChallengeData result = new ChallengeData(
                HttpResponseStatus.UNAUTHORIZED.code(),
                HttpHeaderNames.WWW_AUTHENTICATE,
                "Bearer {token}"
        );
        return Uni.createFrom().item(result);
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Collections.singleton(TokenAuthenticationRequest.class);
    }

    /**
     * Hospital-item paths stay open except stock transfer endpoints (require JWT + role check).
     */
    private boolean isHospitalItemPathWithoutTransferAuth(String path) {
        if (path == null || !path.contains("/health_care/Hospital-item/")) {
            return false;
        }
        return !path.contains("/health_care/Hospital-item/transfer-stock")
                && !path.contains("/health_care/Hospital-item/stock-transfers");
    }

    /** Static Angular shell served from META-INF/resources/app under /health_care/app. */
    private boolean isPublicClinicUiAsset(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        // UI is isolated under /health_care/app so it never clashes with REST modules
        // such as /health_care/assets (inventory) or /health_care/Patient-management.
        return path.equals("/health_care/app")
                || path.equals("/health_care/app/")
                || path.startsWith("/health_care/app/");
    }

    private String parseJwt(HttpServerRequest request) {
        String headerAuth = request.getHeader(AUTHORIZATION_HEADER);
        if (headerAuth != null && headerAuth.startsWith(BEARER)) {
            return headerAuth.substring(7);
        }
        // WebSocket clients pass JWT in query string (?token=...) during the upgrade handshake.
        if (request.path() != null && request.path().contains("/messages/ws")) {
            return extractTokenFromQuery(request.query());
        }
        return null;
    }

    private static String extractTokenFromQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if ("token".equals(part.substring(0, eq))) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}






