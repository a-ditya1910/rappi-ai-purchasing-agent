"""The agent's entire view of the world.

No database driver, no connection string, no credentials. Every fact it reads
and every action it takes is an http call into the spring platform, which
validates independently every time. That is the guardrail - not a line in a
prompt, but a service boundary the model cannot reach around.

X-Run-Id goes on every request, which is what makes the platform's interceptor
write the audit trail without the agent containing any tracing code.
"""
import httpx

from config import cfg


class PlatformError(Exception):
    pass


class Platform:
    def __init__(self, run_id, base_url=None, client=None):
        self.run_id = run_id
        self.base = (base_url or cfg.platform_url).rstrip("/")
        self.http = client or httpx.Client(timeout=30)
        self.calls = []

    def _headers(self):
        return {"X-Run-Id": self.run_id, "Content-Type": "application/json"}

    def get(self, path, **params):
        r = self.http.get(f"{self.base}{path}", params=params, headers=self._headers())
        return self._unwrap(path, r)

    def post(self, path, body):
        r = self.http.post(f"{self.base}{path}", json=body, headers=self._headers())
        return self._unwrap(path, r)

    def _unwrap(self, path, r):
        self.calls.append(path)
        try:
            payload = r.json()
        except Exception:
            raise PlatformError(f"{path} returned non-json: {r.status_code} {r.text[:200]}")

        # tools answer {ok, data, error, suggestions}. an error is a normal
        # return value the model can read and correct against, not an exception.
        if isinstance(payload, dict) and "ok" in payload:
            return payload
        return {"ok": True, "data": payload}

    # ---- reads -----------------------------------------------------------

    def product(self, sku):
        return self.get("/tools/product", sku=sku)

    def inventory_position(self, sku, nodeId):
        return self.get("/tools/inventory-position", sku=sku, nodeId=nodeId)

    def demand_forecast(self, sku, nodeId, horizonDays=14):
        return self.get("/tools/demand-forecast", sku=sku, nodeId=nodeId, horizonDays=horizonDays)

    def sales_actuals(self, sku, nodeId, lookbackDays=60):
        return self.get("/tools/sales-actuals", sku=sku, nodeId=nodeId, lookbackDays=lookbackDays)

    def open_pos(self, sku, nodeId):
        return self.get("/tools/open-pos", sku=sku, nodeId=nodeId)

    def suppliers(self, sku):
        return self.get("/tools/suppliers", sku=sku)

    def budget(self, nodeId, category):
        return self.get("/tools/budget", nodeId=nodeId, category=category)

    def storage(self, nodeId, sku=None):
        return self.get("/tools/storage", nodeId=nodeId, sku=sku) if sku \
            else self.get("/tools/storage", nodeId=nodeId)

    def po(self, poId):
        return self.get(f"/tools/po/{poId}")

    # ---- compute ---------------------------------------------------------

    def calculate_reorder(self, sku, nodeId, supplierId):
        return self.post("/tools/calculate-reorder",
                         {"sku": sku, "nodeId": nodeId, "supplierId": supplierId})

    def validate_purchase(self, sku, nodeId, supplierId, qty, unitPrice,
                          expectedDelivery, recommendedQty=None):
        return self.post("/tools/validate-purchase", {
            "sku": sku, "nodeId": nodeId, "supplierId": supplierId,
            "qty": qty, "unitPrice": unitPrice,
            "expectedDelivery": expectedDelivery, "recommendedQty": recommendedQty,
        })

    # ---- writes ----------------------------------------------------------

    def create_po(self, sku, nodeId, supplierId, qty, unitPrice, expectedDelivery,
                  idempotencyKey, recommendedQty=None, reason=None):
        return self.post("/tools/create-po", {
            "sku": sku, "nodeId": nodeId, "supplierId": supplierId, "qty": qty,
            "unitPrice": unitPrice, "expectedDelivery": expectedDelivery,
            "idempotencyKey": idempotencyKey, "recommendedQty": recommendedQty,
            "reason": reason,
        })

    def amend_po(self, poId, newQty, expectedVersion, reason):
        return self.post("/tools/amend-po", {
            "poId": poId, "newQty": newQty,
            "expectedVersion": expectedVersion, "reason": reason})

    def cancel_po(self, poId, expectedVersion, reason):
        return self.post("/tools/cancel-po", {
            "poId": poId, "expectedVersion": expectedVersion, "reason": reason})

    def request_approval(self, reason, riskTier, proposedAction):
        return self.post("/tools/request-approval", {
            "reason": reason, "riskTier": riskTier, "proposedAction": proposedAction})

    def record_decision(self, decision, finalQty=None, explanation=None, validationReport=None):
        return self.post("/tools/record-decision", {
            "decision": decision, "finalQty": finalQty,
            "explanation": explanation, "validationReport": validationReport})

    def demand_anomaly(self, sku, nodeId, lookbackDays=60):
        return self.get("/tools/demand-anomaly", sku=sku, nodeId=nodeId, lookbackDays=lookbackDays)

    def policy_search(self, query, k=3):
        return self.get("/tools/policy-search", query=query, k=k)

    # ---- run bookkeeping -------------------------------------------------

    def log_step(self, type_, name, payload=None):
        """Agent side events - the model's own reasoning. Tool calls trace
        themselves through the interceptor."""
        return self.post(f"/runs/{self.run_id}/steps",
                         {"type": type_, "name": name, "payload": payload or {}})
