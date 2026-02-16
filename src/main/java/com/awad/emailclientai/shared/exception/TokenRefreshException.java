package com.awad.emailclientai.shared.exception;

/**
 * Token Refresh Exception
 * 
 * <p>Thrown when a refresh token operation fails due to:
 * <ul>
 *   <li>Token expiration</li>
 *   <li>Token not found in database</li>
 *   <li>Token revocation/reuse detection</li>
 * </ul>
 */
public class TokenRefreshException extends UnauthorizedException {

    public TokenRefreshException(String token, String message) {
        super(ErrorCode.REFRESH_TOKEN_EXPIRED, String.format("Failed for [%s]: %s", token, message));
    }

    public TokenRefreshException(String message) {
        super(ErrorCode.REFRESH_TOKEN_EXPIRED, message);
    }
}
