package com.adgendoc.domain.ports;

import com.adgendoc.domain.Template;

import java.util.Optional;

public interface TemplateRepository {

    Optional<Template> findActiveByCode(String code);
}
