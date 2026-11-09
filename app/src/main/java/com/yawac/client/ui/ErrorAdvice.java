package com.yawac.client.ui;

public final class ErrorAdvice {
    private ErrorAdvice() { }

    public static String forError(String error) {
        if (error.contains("SEARCH_PLAN_ERROR")) return "参考需求判断未完成，可重试或关闭联网辅助后独立作答。";
        if (error.contains("NO_SEARCH_RESULTS") || error.contains("EMPTY_SEARCH_QUERY")) return "请使用更明确的关键词重新提问，也可关闭联网后发送。";
        if (error.contains("INVALID_SEARCH_RESPONSE")) return "搜索服务回复异常，请稍后重试。";
        if (error.contains("API_CONFIG_REQUIRED") || error.contains("CONFIG_STORAGE_ERROR")) return "请回主页右滑，在模型供应商设置中填写并保存配置。";
        if (error.contains("UNSUPPORTED_CAPABILITY")) return "此服务暂未适配联网或思考开关，可关闭后重新发送。";
        if (error.contains("HTTP 401") || error.contains("HTTP 403")) return "请检查 API 密钥及服务访问权限。";
        if (error.contains("HTTP 402")) return "请检查 AI 服务账户余额。";
        if (error.contains("HTTP 429")) return "请求过于频繁，请稍等再重试。";
        if (error.contains("NETWORK_ERROR")) return "请检查手表的 Wi-Fi 或移动网络，再点击重试。";
        if (error.contains("TIMEOUT")) return "请检查网络；也可切换快速模式后重新提问。";
        if (error.contains("content_filter")) return "请调整问题后重新发送。";
        if (error.contains("错误码：length")) return "请缩短问题或拆分为几次提问。";
        if (error.contains("HTTP 5") || error.contains("insufficient_system_resource")) return "AI 服务暂时繁忙，请稍后重试。";
        return "可点击重试；如果仍失败，请根据错误码检查服务。";
    }
}
