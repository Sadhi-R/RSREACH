package com.rsreach.billing;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to the plan catalog, plan versions, and plan entitlements.
 * Plans are stored as data; pricing is intentionally unset in dev/CI seed rows.
 */
@Service
public class PlanCatalogService {

    private final JdbcTemplate jdbcTemplate;

    public PlanCatalogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<BillingModels.PlanView> listPlans() {
        return jdbcTemplate.query(
                """
                SELECT id, code, name, description, sort_order, active, created_at
                FROM plan_catalog
                ORDER BY sort_order, name
                """,
                (rs, i) -> mapPlan(rs)
        );
    }

    /**
     * Returns the active plan versions for a given plan, newest first.
     * This is useful when selecting a version to subscribe to.
     */
    @Transactional(readOnly = true)
    public List<BillingModels.PlanVersionView> listVersionsForPlan(UUID planId) {
        return jdbcTemplate.query(
                """
                SELECT id, plan_id, version_number, billing_period, price_paise, currency,
                       trial_days, grace_days, effective_from
                FROM plan_version
                WHERE plan_id = ?
                ORDER BY version_number DESC
                """,
                (rs, i) -> mapPlanVersion(rs),
                planId
        );
    }

    @Transactional(readOnly = true)
    public BillingModels.PlanVersionView getPlanVersion(UUID planVersionId) {
        List<BillingModels.PlanVersionView> rows = jdbcTemplate.query(
                """
                SELECT id, plan_id, version_number, billing_period, price_paise, currency,
                       trial_days, grace_days, effective_from
                FROM plan_version
                WHERE id = ?
                """,
                (rs, i) -> mapPlanVersion(rs),
                planVersionId
        );
        return rows.stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Plan version not found: " + planVersionId));
    }

    @Transactional(readOnly = true)
    public List<BillingModels.EntitlementView> listEntitlements(UUID planVersionId) {
        return jdbcTemplate.query(
                """
                SELECT id, plan_version_id, meter_code, limit_value, soft_limit_pct
                FROM plan_entitlement
                WHERE plan_version_id = ?
                ORDER BY meter_code
                """,
                (rs, i) -> mapEntitlement(rs),
                planVersionId
        );
    }

    private static BillingModels.PlanView mapPlan(ResultSet rs) throws SQLException {
        return new BillingModels.PlanView(
                (UUID) rs.getObject("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("sort_order"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    // Package-private so SubscriptionService can reuse the mapper when joining plan_version.
    static BillingModels.PlanVersionView mapPlanVersion(ResultSet rs) throws SQLException {
        long rawPrice = rs.getLong("price_paise");
        Long pricePaise = rs.wasNull() ? null : rawPrice;
        return new BillingModels.PlanVersionView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("plan_id"),
                rs.getInt("version_number"),
                rs.getString("billing_period"),
                pricePaise,
                rs.getString("currency"),
                rs.getInt("trial_days"),
                rs.getInt("grace_days"),
                rs.getTimestamp("effective_from").toInstant()
        );
    }

    private static BillingModels.EntitlementView mapEntitlement(ResultSet rs) throws SQLException {
        long rawLimit = rs.getLong("limit_value");
        Long limitValue = rs.wasNull() ? null : rawLimit;
        return new BillingModels.EntitlementView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("plan_version_id"),
                rs.getString("meter_code"),
                limitValue,
                rs.getInt("soft_limit_pct")
        );
    }
}
