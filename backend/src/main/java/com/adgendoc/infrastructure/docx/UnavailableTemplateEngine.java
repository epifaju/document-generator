package com.adgendoc.infrastructure.docx;

import com.adgendoc.domain.Template;
import com.adgendoc.domain.exceptions.DocumentGenerationException;
import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.ports.TemplateEngine;

import java.util.Map;

/**
 * Placeholder F1 (phase F1 : pas de génération DOCX). Le moteur réel
 * {@code PoiTemplateEngine} (Apache POI) sera implanté dans une phase
 * ultérieure — il est explicitement hors périmètre F1.
 *
 * <p>Toute tentative de fusion est rejetée avec un code classifié
 * ({@code DOCUMENT_GENERATION_ERROR}) plutôt que de produire un document
 * non approuvé.</p>
 */
public class UnavailableTemplateEngine implements TemplateEngine {

    @Override
    public byte[] merge(Template template, Map<String, String> templateVariables) {
        throw new DocumentGenerationException(ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage());
    }
}
