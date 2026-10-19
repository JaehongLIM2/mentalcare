package com.mentalcare.context;

/** Test-only access to package-private helpers of the context builder. */
public final class CounselingContextBuilderAccess {
    private CounselingContextBuilderAccess() {}

    public static String gapPhrase(long days) {
        return CounselingContextBuilder.gapPhrase(days);
    }
}
