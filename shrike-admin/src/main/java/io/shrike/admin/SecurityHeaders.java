package io.shrike.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Two headers on every answer this facade writes, whatever its status.
 *
 * <p>This facade produces JSON and nothing else: no page, no script, no stylesheet, no image, and
 * nothing a browser is meant to render. The headers say exactly that, so that a browser pointed at one
 * of these answers cannot be talked into treating it as something else.
 *
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — take the content type this facade declared and do
 *       not guess a better one from the bytes. Every answer here is {@code application/json} and names
 *       itself so; sniffing is what turns a body somebody chose the first bytes of into a document.
 *   <li>{@code Content-Security-Policy: default-src 'none'; frame-ancestors 'none'} — if one of these
 *       answers is ever treated as a document anyway, it may load nothing at all, and no page may put
 *       it in a frame. Both are the empty policy rather than a tuned one, because nothing this facade
 *       answers has a resource to load or a reason to be framed.
 * </ul>
 *
 * <p><strong>Including the answers this facade did not choose.</strong> A path the container refuses
 * itself — {@code /WEB-INF} and {@code /META-INF} — never reaches the filter chain on its first pass
 * and arrives at {@link ErrorEndpoint} as a forward, which is a dispatch a
 * {@link OncePerRequestFilter} skips unless it says otherwise. This one says otherwise: an error
 * answer is an answer, and it leaves this process through the same socket.
 *
 * <p>The one thing it cannot reach is a request Tomcat refuses while it is still parsing bytes — a
 * broken percent-escape in the request target — because no filter of this facade's runs on one. That
 * answer is a status line with no body at all, which is nothing to sniff and nothing to frame;
 * {@link ShrikeAdminApplication#containerErrorReports()} is where that is arranged.
 */
@Component
public class SecurityHeaders extends OncePerRequestFilter {

    /** Take the declared content type; do not guess one from the bytes. */
    static final String NO_SNIFFING = "X-Content-Type-Options";

    /** Load nothing, and be framed by nobody. */
    static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    static final String NOSNIFF = "nosniff";

    static final String LOAD_NOTHING_AND_BE_FRAMED_BY_NOBODY = "default-src 'none'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Before the chain rather than after it: whatever answers this request may have committed the
        // response by the time it returns, and a header set after that is a header nobody receives.
        response.setHeader(NO_SNIFFING, NOSNIFF);
        response.setHeader(CONTENT_SECURITY_POLICY, LOAD_NOTHING_AND_BE_FRAMED_BY_NOBODY);
        chain.doFilter(request, response);
    }

    /**
     * @return false, because the container's forward to {@code /error} is where a failure it settled
     *         on its own becomes an answer, and an answer carries these headers
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
