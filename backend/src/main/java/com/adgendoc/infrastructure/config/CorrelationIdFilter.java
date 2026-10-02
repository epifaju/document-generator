package com.adgendoc.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Filtre 1 : en-tête {@code X-Correlation-Id} (contrat API §1.1,
 * architecture §4.1). En entrée optionnel ; toujours généré/validé, placé en
 * MDC, exposé en réponse et lisible par {@code GlobalExceptionHandler} et les
 * contrôleurs via {@link #currentCorrelationId(HttpServletRequest)}.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = "adgendoc.correlationId";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9\\-]{1,64}$");

    /**
     * Valeur de corrélation de la requête courante : attribut du filtre, sinon
     * en-tête entrant s'il est valide, sinon un nouvel identifiant.
     */
    public static String currentCorrelationId(HttpServletRequest request) {
        Object attribute = request.getAttribute(ATTRIBUTE);
        if (attribute != null && !attribute.toString().isBlank()) {
            return attribute.toString();
        }
        String header = request.getHeader(HEADER);
        if (header != null && VALID_CORRELATION_ID.matcher(header).matches()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = incoming != null && VALID_CORRELATION_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
