package com.mentalcare.auth;

/** Failure in the Sign in with ChatGPT flow. Messages never contain tokens, codes or verifiers. */
public class AuthException extends RuntimeException {

    public enum Code {
        CONFIG, NETWORK, DISCOVERY, STATE_MISMATCH, ACCESS_DENIED, REGISTRATION_INCOMPLETE,
        CLIENT_ID_MISMATCH, TOKEN_EXCHANGE, INVALID_ID_TOKEN, STORAGE, REAUTH_REQUIRED, NOT_CONNECTED, INVALID_REQUEST
    }

    private final Code code;

    public AuthException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public AuthException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public String userMessage() {
        return switch (code) {
            case CONFIG -> "Sign in with ChatGPT 설정이 올바르지 않습니다.";
            case NETWORK -> "OpenAI 인증 서버에 연결할 수 없습니다. 네트워크를 확인하세요.";
            case DISCOVERY -> "OpenAI 인증 서버 설정(discovery)을 확인하지 못했습니다.";
            case STATE_MISMATCH -> "로그인 요청을 확인할 수 없습니다(state 불일치). 다시 시도하세요.";
            case ACCESS_DENIED -> "ChatGPT 로그인이 취소되었습니다.";
            case REGISTRATION_INCOMPLETE -> "OpenAI가 앱 등록(client_id)을 돌려주지 않았습니다. 다시 시도하세요.";
            case CLIENT_ID_MISMATCH -> "돌아온 client_id가 요청과 다릅니다. 로그인을 중단했습니다.";
            case TOKEN_EXCHANGE -> "인증 코드를 토큰으로 교환하지 못했습니다. 다시 시도하세요.";
            case INVALID_ID_TOKEN -> "ID 토큰 검증에 실패했습니다. 로그인을 중단했습니다.";
            case STORAGE -> "자격 증명을 안전하게 저장하지 못했습니다.";
            case REAUTH_REQUIRED -> "ChatGPT 연결이 만료되었습니다. 다시 로그인하세요.";
            case NOT_CONNECTED -> "ChatGPT 계정이 연결되어 있지 않습니다.";
            case INVALID_REQUEST -> "잘못된 요청입니다.";
        };
    }
}
