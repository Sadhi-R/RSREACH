package com.rsreach.billing;

import java.util.UUID;

/**
 * Port for payment provider adapters.
 *
 * <p>MVP has one adapter: {@link RazorpayPaymentGateway}. A future gateway implements this
 * interface without touching billing business logic.
 *
 * <p>All amounts are integer paise. Currency is an ISO-4217 code; INR is the initial value.
 */
public interface PaymentGateway {

    /** Machine-readable provider identifier stored on billing_payment.provider. */
    String provider();

    /**
     * Creates a payment order for the given amount.
     *
     * @param tenantId       the tenant initiating checkout
     * @param subscriptionId the subscription being renewed
     * @param amountPaise    amount in integer paise; never floating-point
     * @param currency       ISO-4217 currency code (e.g. "INR")
     * @param receipt        human-readable receipt reference for the provider
     * @return a {@link CheckoutOrder} with enough data to present Razorpay Checkout in the browser
     */
    CheckoutOrder createOrder(UUID tenantId, UUID subscriptionId,
            long amountPaise, String currency, String receipt);

    /**
     * Verifies the webhook signature supplied by the provider.
     *
     * @param signatureHeader value of the provider-specific signature header (e.g. X-Razorpay-Signature)
     * @param rawBody         the unmodified raw request body bytes as received from the provider
     * @return {@code true} if the signature is valid; {@code false} otherwise
     */
    boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody);

    /**
     * Provider-issued order ready for client-side checkout.
     *
     * @param orderId     provider order ID (e.g. "order_..." from Razorpay)
     * @param currency    ISO-4217 currency code
     * @param amountPaise total amount in paise
     * @param keyId       public API key to pass to the client-side Razorpay SDK
     */
    record CheckoutOrder(String orderId, String currency, long amountPaise, String keyId) {
    }
}
