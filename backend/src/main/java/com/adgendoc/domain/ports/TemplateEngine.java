package com.adgendoc.domain.ports;

import com.adgendoc.domain.Template;

import java.util.Map;

public interface TemplateEngine {

    byte[] merge(Template template, Map<String, String> templateVariables);
}
