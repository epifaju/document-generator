package com.adgendoc.domain.exceptions;

import com.adgendoc.domain.ErrorCode;

public class TemplateNotFoundException extends RuntimeException {

    private final transient ErrorCode errorCode = ErrorCode.TEMPLATE_NOT_FOUND;
    private final String templateCode;

    public TemplateNotFoundException(String templateCode) {
        super("Template officiel introuvable ou intégrité altérée : " + templateCode);
        this.templateCode = templateCode;
    }

    public TemplateNotFoundException(String templateCode, Throwable cause) {
        super("Template officiel introuvable ou intégrité altérée : " + templateCode, cause);
        this.templateCode = templateCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getTemplateCode() {
        return templateCode;
    }
}
