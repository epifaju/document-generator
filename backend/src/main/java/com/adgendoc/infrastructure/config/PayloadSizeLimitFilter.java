package com.adgendoc.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Filtre 2 : rejette les corps trop volumineux (413) avant toute
 * désérialisation (contrat architecture §12). Priorité à l'en-tête
 * {@code Content-Length} ; à défaut (transfer encodé), le flux d'entrée est
 * borné et déclenche {@link PayloadTooLargeIOException}, converti en 413 par
 * {@code GlobalExceptionHandler}.
 */
@Component
@Order(2)
public class PayloadSizeLimitFilter extends OncePerRequestFilter {

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    private final long maxPayloadBytes;

    public PayloadSizeLimitFilter(long maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!BODY_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        long declaredLength = declaredContentLength(request);
        if (declaredLength > maxPayloadBytes) {
            reject(response, request);
            return;
        }
        if (declaredLength < 0) {
            filterChain.doFilter(new BoundedRequestWrapper(request, maxPayloadBytes), response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private long declaredContentLength(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.CONTENT_LENGTH);
        if (header != null) {
            try {
                return Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) {
                return request.getContentLengthLong();
            }
        }
        return request.getContentLengthLong();
    }

    private void reject(HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        String correlationId = CorrelationIdFilter.currentCorrelationId(request);
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"VALIDATION_ERROR\","
                + "\"message\":\"Corps de requ\u00eate trop volumineux.\","
                + "\"correlationId\":\"" + correlationId + "\","
                + "\"missingFields\":[],\"fieldErrors\":[]}");
    }

    /** Erreur levée quand un corps excède la limite à la lecture du flux. */
    public static class PayloadTooLargeIOException extends IOException {

        public PayloadTooLargeIOException(String message) {
            super(message);
        }
    }

    private static final class BoundedRequestWrapper extends HttpServletRequestWrapper {

        private final long maxBytes;

        private BoundedRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new BoundedServletInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
        }
    }

    private static final class BoundedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long count;

        private BoundedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int next = delegate.read();
            if (next >= 0) {
                count++;
                if (count > maxBytes) {
                    throw new PayloadTooLargeIOException(
                            "Corps de requ\u00eate sup\u00e9rieur \u00e0 " + maxBytes + " octets.");
                }
            }
            return next;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
