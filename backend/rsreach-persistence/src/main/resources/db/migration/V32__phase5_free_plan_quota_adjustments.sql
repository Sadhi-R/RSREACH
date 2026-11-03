-- Phase 5: provisional Free quotas for multi-account CI/dev flows (not commercial D2).
-- CONNECTED_ACCOUNTS was 1 which blocked Phase 3 IT connecting WhatsApp + Gmail.

UPDATE plan_entitlement pe
SET limit_value = 10
FROM plan_version pv
JOIN plan_catalog pc ON pc.id = pv.plan_id
WHERE pe.plan_version_id = pv.id
  AND pc.code = 'FREE'
  AND pe.meter_code = 'CONNECTED_ACCOUNTS';

UPDATE plan_entitlement pe
SET limit_value = 25
FROM plan_version pv
JOIN plan_catalog pc ON pc.id = pv.plan_id
WHERE pe.plan_version_id = pv.id
  AND pc.code = 'FREE'
  AND pe.meter_code = 'MEMBERS';
