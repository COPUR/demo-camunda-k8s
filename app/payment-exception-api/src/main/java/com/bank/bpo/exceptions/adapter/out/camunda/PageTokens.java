package com.bank.bpo.exceptions.adapter.out.camunda;

import com.bank.bpo.exceptions.domain.shared.DomainException;
import com.bank.bpo.exceptions.domain.shared.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The engine's search cursor wrapped as an opaque page token (base64url), so clients cannot depend on its form. */
final class PageTokens {

    private PageTokens() {
    }

    static String token(String cursor) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
    }

    static String cursor(String token) {
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.INVALID_PAGE_REQUEST, "page_token is not a token this API issued");
        }
    }
}
