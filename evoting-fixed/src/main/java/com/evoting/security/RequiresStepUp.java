package com.evoting.security;

import java.lang.annotation.*;

/**
 * Marks a controller method as requiring ECDSA step-up authentication.
 *
 * The client must include these headers on the request:
 *   X-Action-Signature: <base64 ECDSA signature of "ACTION_TYPE:NONCE">
 *   X-Action-Nonce:     <nonce from GET /api/auth/challenge>
 *
 * The StepUpAuthFilter verifies these before the method executes.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresStepUp {
    /** The action type — must match one of StepUpAuthService.PROTECTED_ACTIONS */
    String value();
}
