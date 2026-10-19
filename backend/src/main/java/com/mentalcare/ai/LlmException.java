package com.mentalcare.ai;

/** Provider-independent failure of an AI call. The message never contains counseling text or tokens. */
public class LlmException extends RuntimeException {

    public enum Kind {
        // configuration / authentication
        NOT_CONFIGURED, NOT_CONNECTED, NO_PLAN_USAGE, AUTH_REQUIRED,
        // transport
        NETWORK, TIMEOUT, RATE_LIMIT, SERVER, BAD_REQUEST,
        // ChatGPT plan usage
        PLAN_LIMIT, PLAN_NOT_ELIGIBLE, PLAN_UNAVAILABLE, UNSUPPORTED_CAPABILITY, ROUTE_NOT_SUPPORTED,
        // model output
        REFUSAL, INCOMPLETE, PARSE_FAILED, STREAM_INTERRUPTED, UNKNOWN
    }

    private final Kind kind;
    private final String providerCode;

    public LlmException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        this(kind, message, null, cause);
    }

    public LlmException(Kind kind, String message, String providerCode, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.providerCode = providerCode;
    }

    public Kind kind() {
        return kind;
    }

    /** Provider error code such as {@code subscription_sharing_usage_limit_exceeded}, if any. */
    public String providerCode() {
        return providerCode;
    }

    /** True when the user has to sign in again (or connect) before AI features work. */
    public boolean requiresSignIn() {
        return kind == Kind.NOT_CONNECTED || kind == Kind.AUTH_REQUIRED || kind == Kind.NO_PLAN_USAGE;
    }

    /** User-facing (Korean) explanation suitable for the UI. */
    public String userMessage() {
        return switch (kind) {
            case NOT_CONFIGURED -> "OpenAI API 키가 설정되어 있지 않습니다. OPENAI_API_KEY 환경변수를 확인하세요.";
            case NOT_CONNECTED -> "ChatGPT 계정이 연결되어 있지 않습니다. 설정에서 'Continue with ChatGPT'로 연결하세요.";
            case NO_PLAN_USAGE -> "ChatGPT 로그인은 되었지만 ChatGPT 플랜 사용 권한이 승인되지 않았습니다. 설정에서 권한을 다시 승인하세요.";
            case AUTH_REQUIRED -> "ChatGPT 연결이 만료되었거나 해제되었습니다. 설정에서 다시 로그인하세요.";
            case NETWORK -> "OpenAI 서버에 연결할 수 없습니다. 네트워크를 확인한 뒤 다시 시도하세요.";
            case TIMEOUT -> "OpenAI 응답이 시간 내에 오지 않았습니다. 잠시 후 다시 시도하세요.";
            case RATE_LIMIT -> "요청이 너무 잦습니다. 잠시 후 다시 시도하세요.";
            case SERVER -> "OpenAI 서버 오류가 발생했습니다. 잠시 후 다시 시도하세요.";
            case BAD_REQUEST -> "OpenAI 요청이 거부되었습니다(설정 또는 모델명을 확인하세요).";
            case PLAN_LIMIT -> "ChatGPT 플랜 사용 한도에 도달했습니다. ChatGPT 설정의 Usage에서 확인하세요. 별도 결제는 발생하지 않습니다.";
            case PLAN_NOT_ELIGIBLE -> "이 ChatGPT 계정은 앱에서 플랜 사용이 허용되지 않습니다.";
            case PLAN_UNAVAILABLE -> "ChatGPT 플랜 사용 확인이 일시적으로 불가능합니다. 잠시 후 다시 시도하세요.";
            case UNSUPPORTED_CAPABILITY -> "ChatGPT 플랜 사용에서 지원하지 않는 요청 옵션이 포함되어 있습니다.";
            case ROUTE_NOT_SUPPORTED -> "ChatGPT 플랜 사용에서 지원하지 않는 API 경로입니다.";
            case REFUSAL -> "모델이 이 요청에 대한 응답을 거부했습니다.";
            case INCOMPLETE -> "모델 응답이 중간에 끊겼습니다. 다시 시도하세요.";
            case PARSE_FAILED -> "모델 응답을 해석하지 못했습니다. 다시 시도하세요.";
            case STREAM_INTERRUPTED -> "응답 스트리밍이 중단되었습니다.";
            case UNKNOWN -> "AI 호출 중 알 수 없는 오류가 발생했습니다.";
        };
    }
}
